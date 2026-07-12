package com.foggy.navigator.gemini.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.AgentMessageBuilder;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.common.model.GeminiConfig;
import com.foggy.navigator.gemini.worker.client.GeminiWorkerClient;
import com.foggy.navigator.gemini.worker.client.GeminiWorkerClientFactory;
import com.foggy.navigator.gemini.worker.model.entity.GeminiTaskEntity;
import com.foggy.navigator.gemini.worker.repository.GeminiTaskRepository;
import com.foggy.navigator.session.event.SessionEventListener;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gemini Worker SSE -> AgentMessage 桥接
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiStreamRelay {

    private static final long DURABLE_PERSISTENCE_RECOVERY_DELAY_MS = 1_000L;

    private final WorkerManagementFacade workerManagementFacade;
    private final GeminiWorkerClientFactory clientFactory;
    private final GeminiTaskService taskService;
    private final GeminiTaskRepository taskRepository;
    private final SessionEventListener sessionEventListener;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Disposable> activeStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> lastAckedSeq = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> observedWorkerTaskIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Disposable> durableRecoveryTasks = new ConcurrentHashMap<>();

    @Async("sessionEventExecutor")
    @EventListener(condition = "#event.providerType == 'gemini-worker'")
    public void onTaskStart(WorkerTaskStartEvent event) {
        String taskId = event.getTaskId();
        publishMessage(event.getSessionId(), MessageType.SESSION_START, Map.of(
                "content", "Connecting to Gemini worker...",
                "taskId", taskId
        ));
        try {
            GeminiWorkerClient client = getGeminiClient(event.getWorkerId());
            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedSessionId = new AtomicReference<>(event.getProviderConfigString("geminiSessionId"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = event.getProviderConfigValue("attachments");
            Flux<ServerSentEvent<String>> sseFlux = client.streamQuery(
                    event.getPrompt(),
                    event.getCwd(),
                    event.getProviderConfigString("geminiSessionId"),
                    event.getModel(),
                    event.getMaxTurns(),
                    event.getApiKey(),
                    event.getProviderConfigString("baseUrl"),
                    getExtraEnvVars(event),
                    attachments);
            Disposable disposable = sseFlux.subscribe(
                    sse -> handleSseEvent(sse, taskId, event.getSessionId(), detectedModel, detectedSessionId),
                    error -> {
                        if (error instanceof DurableWorkerEventPersistenceException) {
                            // The Worker may replay this ESN from the persisted ACK.  In
                            // particular, do not turn a local message/descriptor write
                            // outage into a terminal upstream task failure.
                            log.warn("Gemini stream stopped after durable persistence failure: taskId={}", taskId);
                            activeStreams.remove(taskId);
                            scheduleDurablePersistenceRecovery(taskId, event.getSessionId(), event.getWorkerId());
                            return;
                        }
                        log.warn("Gemini stream failed: taskId={}, error={}", taskId, error.getMessage());
                        taskService.failTask(taskId, null, detectedSessionId.get(), error.getMessage());
                        activeStreams.remove(taskId);
                    },
                    () -> activeStreams.remove(taskId)
            );
            registerActiveStream(taskId, disposable);
        } catch (Exception e) {
            log.error("Failed to start Gemini stream relay: taskId={}", taskId, e);
            taskService.failTask(taskId, null, event.getProviderConfigString("geminiSessionId"), e.getMessage());
        }
    }

    public void reconnectTask(String taskId, String sessionId, String workerId) {
        resumeFromDurableAck(taskId, sessionId, workerId);
    }

    private boolean resumeFromDurableAck(String taskId, String sessionId, String workerId) {
        try {
            GeminiTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
            if (entity == null || !isRecoverableTaskStatus(entity.getStatus())) {
                return true;
            }
            String workerTaskId = firstNonBlank(entity.getWorkerTaskId(), observedWorkerTaskIds.get(taskId));
            if (workerTaskId == null) {
                log.warn("Cannot reconnect Gemini task without an upstream task id: taskId={}", taskId);
                return false;
            }
            GeminiWorkerClient client = getGeminiClient(workerId);
            int ackSeq = entity.getLastAckedSeq() != null ? entity.getLastAckedSeq() : 0;
            AtomicReference<String> detectedModel = new AtomicReference<>(entity.getModel());
            AtomicReference<String> detectedSessionId = new AtomicReference<>(entity.getGeminiSessionId());
            Disposable disposable = client.subscribeToTask(workerTaskId, ackSeq).subscribe(
                    sse -> handleSseEvent(sse, taskId, sessionId, detectedModel, detectedSessionId),
                    error -> handleReconnectError(error, taskId, sessionId, workerId),
                    () -> activeStreams.remove(taskId)
            );
            registerActiveStream(taskId, disposable);
            return true;
        } catch (Exception e) {
            log.warn("Failed to reconnect Gemini task {}: {}", taskId, e.getMessage());
            return false;
        }
    }

    public void abortStream(String taskId) {
        Disposable disposable = activeStreams.remove(taskId);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        lastAckedSeq.remove(taskId);
        observedWorkerTaskIds.remove(taskId);
        cancelDurablePersistenceRecovery(taskId);
    }

    public void abortRemoteTask(GeminiTaskEntity task) {
        if (task == null || task.getWorkerTaskId() == null || task.getWorkerTaskId().isBlank()) {
            return;
        }
        try {
            getGeminiClient(task.getWorkerId()).abortTask(task.getWorkerTaskId()).block(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("Failed to abort upstream Gemini task: localTaskId={}, workerTaskId={}, error={}",
                    task.getTaskId(), task.getWorkerTaskId(), e.getMessage());
        }
    }

    private void handleSseEvent(ServerSentEvent<String> sse, String taskId, String sessionId,
                                AtomicReference<String> detectedModel,
                                AtomicReference<String> detectedSessionId) {
        String data = sse.data();
        if (data == null || data.isBlank()) {
            return;
        }
        WorkerEvent event = null;
        boolean sequencedWorkerEvent = false;
        try {
            event = objectMapper.readValue(data, WorkerEvent.class);
            sequencedWorkerEvent = event.getSeq() != null;
            AtomicInteger ackTracker = lastAckedSeq.computeIfAbsent(taskId, ignored -> new AtomicInteger());
            Integer ackSeq = sequencedWorkerEvent ? event.getSeq() : ackTracker.get() + 1;

            String eventSessionId = blankToNull(event.getSessionId());
            if (eventSessionId == null) {
                eventSessionId = dataString(event, "geminiSessionId");
            }
            if (eventSessionId != null && !eventSessionId.isBlank()) {
                detectedSessionId.set(eventSessionId);
            }
            String eventModel = blankToNull(event.getModel());
            if (eventModel != null && !eventModel.isBlank()) {
                detectedModel.set(eventModel);
            }

            String workerTaskId = blankToNull(event.getTaskId());
            if (workerTaskId != null) {
                // This is only an in-memory reconnect hint until
                // recordWorkerProgress/rememberWorkerIdentity succeeds; it is
                // not an ACK and must not move the durable cursor.
                observedWorkerTaskIds.put(taskId, workerTaskId);
            }
            if (sequencedWorkerEvent) {
                // Deliberately do not set ackSeq here. If this identity write
                // or the later session message write fails, replay resumes at
                // the previously durable cursor instead of skipping this ESN.
                taskService.rememberWorkerIdentity(taskId, workerTaskId,
                        detectedSessionId.get(), detectedModel.get());
            }

            relayWorkerEvent(sessionId, taskId, event, detectedSessionId.get());
            if (!isTerminalWorkerEvent(event)) {
                // Both ESN and legacy counter modes advance only after the
                // message/descriptor write has completed successfully.
                taskService.recordWorkerProgress(taskId, event.getTaskId(), detectedSessionId.get(), detectedModel.get(),
                        ackSeq, isUserVisibleOutputEvent(event));
                ackTracker.updateAndGet(current -> Math.max(current, ackSeq));
            }
        } catch (DurableWorkerEventPersistenceException e) {
            // Do not advance a replayable Worker cursor after a MySQL or
            // descriptor persistence failure. Propagating terminates this
            // subscription; reconnectTask will resume from the durable ACK.
            throw e;
        } catch (Exception e) {
            // Do not let either protocol move local progress after a failed
            // persistence step. Legacy recovery is bounded by whatever replay
            // support its Worker provides, but it is never acknowledged here.
            String messageId = event != null
                    ? workerMessageId(taskId, event, "event")
                    : "gemini-worker-event:" + taskId;
            throw new DurableWorkerEventPersistenceException(messageId, e);
        }
    }

    private void relayWorkerEvent(String sessionId, String taskId, WorkerEvent event, String geminiSessionId) {
        String type = event.getType();
        if (type == null) {
            return;
        }

        AgentMessageBuilder mb = AgentMessageBuilder.create(sessionId, GeminiTaskService.AGENT_ID)
                .taskId(taskId)
                .put("geminiSessionId", geminiSessionId);
        String workerMessageId = workerMessageId(taskId, event, "event");

        switch (type) {
            case "assistant_text" -> {
                if ("sync_checkpoint".equals(event.getSubtype())) {
                    return;
                }
                publishBuilt(mb.textChunk(event.getContent() != null ? event.getContent() : ""), workerMessageId);
            }
            case "tool_use" -> publishBuilt(mb.toolCallStart(event.getToolUseId(), event.getTool(), event.getInput()), workerMessageId);
            case "tool_result" -> {
                boolean success = event.getIsError() == null || !event.getIsError();
                publishBuilt(mb.toolCallResult(event.getToolUseId(), event.getTool(), event.getOutput(), success), workerMessageId);
            }
            case "result" -> {
                String resultText = firstNonBlank(event.getContent(), event.getResult());
                publishBuilt(AgentMessageBuilder.create(sessionId, GeminiTaskService.AGENT_ID)
                        .taskId(taskId)
                        .put("geminiSessionId", geminiSessionId)
                        .textComplete(resultText), workerMessageId(taskId, event, "assistant"));

                AgentMessageBuilder resultBuilder = AgentMessageBuilder.create(sessionId, GeminiTaskService.AGENT_ID)
                        .taskId(taskId)
                        .put("geminiSessionId", geminiSessionId)
                        .result(resultText)
                        .metrics(event.getCostUsd(), event.getDurationMs(),
                                event.getInputTokens(), event.getOutputTokens(),
                                event.getNumTurns(), event.getModel());
                publishEvent(resultBuilder.build(MessageType.SESSION_END), workerMessageId(taskId, event, "terminal"));
                if (event.getSeq() != null) {
                    taskService.completeTask(
                            taskId,
                            event.getTaskId(),
                            geminiSessionId,
                            resultText,
                            event.getCostUsd(),
                            event.getInputTokens(),
                            event.getOutputTokens(),
                            event.getDurationMs(),
                            event.getNumTurns(),
                            event.getModel(),
                            event.getSeq());
                } else {
                    taskService.completeTask(
                            taskId,
                            event.getTaskId(),
                            geminiSessionId,
                            resultText,
                            event.getCostUsd(),
                            event.getInputTokens(),
                            event.getOutputTokens(),
                            event.getDurationMs(),
                            event.getNumTurns(),
                            event.getModel());
                }
                // The terminal service method stores the durable cursor in the
                // same transaction as the task result. Only then may the
                // in-memory replay state be discarded.
                lastAckedSeq.remove(taskId);
                activeStreams.remove(taskId);
                observedWorkerTaskIds.remove(taskId);
                cancelDurablePersistenceRecovery(taskId);
            }
            case "error" -> {
                String errorText = firstNonBlank(event.getError(), "Unknown Gemini worker error");
                publishBuilt(mb.error(errorText), workerMessageId);
                if (event.getSeq() != null) {
                    taskService.failTask(taskId, event.getTaskId(), geminiSessionId, errorText, event.getSeq());
                } else {
                    taskService.failTask(taskId, event.getTaskId(), geminiSessionId, errorText);
                }
                // See the result branch: do not clear replay state before the
                // terminal task/ACK transaction has committed.
                lastAckedSeq.remove(taskId);
                activeStreams.remove(taskId);
                observedWorkerTaskIds.remove(taskId);
                cancelDurablePersistenceRecovery(taskId);
            }
            default -> log.debug("Unhandled Gemini event type: {}", type);
        }
    }

    private void publishMessage(String sessionId, MessageType type, Map<String, Object> payload) {
        eventPublisher.publishEvent(AgentMessage.of(sessionId, GeminiTaskService.AGENT_ID, type, payload));
    }

    private boolean isTerminalWorkerEvent(WorkerEvent event) {
        return event != null && ("result".equals(event.getType()) || "error".equals(event.getType()));
    }

    private void publishBuilt(AgentMessageBuilder builder, String messageId) {
        publishEvent(builder.build(), messageId);
    }

    private void registerActiveStream(String taskId, Disposable disposable) {
        // A synchronous Flux can complete or fail before subscribe() returns.
        // Do not retain its already-disposed handle as an active subscription.
        if (!disposable.isDisposed()) {
            activeStreams.put(taskId, disposable);
        }
    }

    private void handleReconnectError(Throwable error, String taskId, String sessionId, String workerId) {
        if (error instanceof DurableWorkerEventPersistenceException) {
            log.warn("Gemini replay stopped after durable persistence failure: taskId={}", taskId);
            activeStreams.remove(taskId);
            scheduleDurablePersistenceRecovery(taskId, sessionId, workerId);
            return;
        }
        log.warn("Reconnect Gemini stream failed: taskId={}, error={}", taskId, error.getMessage());
        activeStreams.remove(taskId);
    }

    private void scheduleDurablePersistenceRecovery(String taskId, String sessionId, String workerId) {
        if (durableRecoveryTasks.containsKey(taskId)) {
            return;
        }
        AtomicReference<Disposable> scheduledRef = new AtomicReference<>();
        Disposable scheduled = Schedulers.boundedElastic().schedule(() -> {
            durableRecoveryTasks.remove(taskId, scheduledRef.get());
            if (!resumeFromDurableAck(taskId, sessionId, workerId)) {
                // The descriptor/message transaction did not advance the
                // Worker cursor. Retry slowly until the durable source and
                // its upstream task identity are available again.
                scheduleDurablePersistenceRecovery(taskId, sessionId, workerId);
            }
        }, DURABLE_PERSISTENCE_RECOVERY_DELAY_MS, TimeUnit.MILLISECONDS);
        scheduledRef.set(scheduled);
        Disposable previous = durableRecoveryTasks.putIfAbsent(taskId, scheduled);
        if (previous != null && !scheduled.isDisposed()) {
            scheduled.dispose();
        }
    }

    private void cancelDurablePersistenceRecovery(String taskId) {
        Disposable scheduled = durableRecoveryTasks.remove(taskId);
        if (scheduled != null && !scheduled.isDisposed()) {
            scheduled.dispose();
        }
    }

    private void publishEvent(AgentMessage message, String messageId) {
        if (messageId != null) {
            message.setMessageId(messageId);
        }
        try {
            sessionEventListener.handleMessageDurably(message);
        } catch (RuntimeException e) {
            throw new DurableWorkerEventPersistenceException(message.getMessageId(), e);
        }
        eventPublisher.publishEvent(message);
    }

    /**
     * Gemini's ESN is stable across subscribe replay. Legacy tool results
     * normally retain tool_use_id, which safely prevents a replay from
     * creating another external payload. Other legacy event kinds keep their
     * generated UUIDs so independent text/status events cannot be merged.
     */
    private String workerMessageId(String taskId, WorkerEvent event, String eventPart) {
        if (event.getSeq() != null) {
            return "gemini-event:" + taskId + ":" + event.getSeq() + ":" + eventPart;
        }
        if (!"tool_result".equals(event.getType())) {
            return null;
        }
        if (event.getToolUseId() != null && !event.getToolUseId().isBlank()) {
            return "gm-lt:" + taskId + ":" + event.getToolUseId();
        }
        return legacyToolMessageId(taskId, event);
    }

    private String legacyToolMessageId(String taskId, WorkerEvent event) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((taskId + "\u0000" + event.getTool() + "\u0000"
                    + event.getOutput() + "\u0000" + event.getIsError())
                    .getBytes(StandardCharsets.UTF_8));
            return "gm-lt-" + HexFormat.of().formatHex(hash, 0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static final class DurableWorkerEventPersistenceException extends RuntimeException {
        private DurableWorkerEventPersistenceException(String messageId, Throwable cause) {
            super("Failed to durably persist Gemini Worker event " + messageId, cause);
        }
    }

    private boolean isUserVisibleOutputEvent(WorkerEvent event) {
        if (event == null || event.getType() == null) {
            return false;
        }
        return switch (event.getType()) {
            case "assistant_text" -> !"sync_checkpoint".equals(event.getSubtype());
            case "tool_use", "tool_result", "result", "error" -> true;
            case "system", "progress" -> isVisibleStatusEvent(event);
            default -> false;
        };
    }

    private boolean isVisibleStatusEvent(WorkerEvent event) {
        String subtype = event.getSubtype();
        if (subtype == null || subtype.isBlank()) {
            return event.getContent() != null && !event.getContent().isBlank();
        }
        String normalized = subtype.toLowerCase(Locale.ROOT);
        if ("waiting".equals(normalized)
                || normalized.contains("heartbeat")
                || normalized.contains("keepalive")
                || "sync_checkpoint".equals(normalized)) {
            return false;
        }
        return event.getContent() != null && !event.getContent().isBlank();
    }

    private GeminiWorkerClient getGeminiClient(String workerId) {
        GeminiConfig config = workerManagementFacade.getGeminiConfig(workerId);
        if (config == null || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Gemini not configured for worker: " + workerId);
        }
        return clientFactory.getOrCreate(workerId + ":gemini", config.getBaseUrl(), config.getAuthToken());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> getExtraEnvVars(WorkerTaskStartEvent event) {
        return (Map<String, String>) event.getProviderConfig().get("extraEnvVars");
    }

    private static String dataString(WorkerEvent event, String key) {
        if (event.getData() == null) {
            return null;
        }
        Object value = event.getData().get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static String firstNonBlank(String first, String second) {
        String firstValue = blankToNull(first);
        return firstValue != null ? firstValue : blankToNull(second);
    }

    private static boolean isRecoverableTaskStatus(String status) {
        return "RUNNING".equals(status) || "AWAITING_PERMISSION".equals(status);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
