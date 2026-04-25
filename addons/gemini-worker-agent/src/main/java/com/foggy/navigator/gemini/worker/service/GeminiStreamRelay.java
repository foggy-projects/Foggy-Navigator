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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gemini Worker SSE -> AgentMessage 桥接
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiStreamRelay {

    private final WorkerManagementFacade workerManagementFacade;
    private final GeminiWorkerClientFactory clientFactory;
    private final GeminiTaskService taskService;
    private final GeminiTaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Disposable> activeStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> lastAckedSeq = new ConcurrentHashMap<>();

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
            Flux<ServerSentEvent<String>> sseFlux = client.streamQuery(
                    event.getPrompt(),
                    event.getCwd(),
                    event.getProviderConfigString("geminiSessionId"),
                    event.getModel(),
                    event.getMaxTurns(),
                    event.getApiKey(),
                    event.getProviderConfigString("baseUrl"),
                    getExtraEnvVars(event));
            Disposable disposable = sseFlux.subscribe(
                    sse -> handleSseEvent(sse, taskId, event.getSessionId(), detectedModel, detectedSessionId),
                    error -> {
                        log.warn("Gemini stream failed: taskId={}, error={}", taskId, error.getMessage());
                        taskService.failTask(taskId, null, detectedSessionId.get(), error.getMessage());
                        activeStreams.remove(taskId);
                    },
                    () -> activeStreams.remove(taskId)
            );
            activeStreams.put(taskId, disposable);
        } catch (Exception e) {
            log.error("Failed to start Gemini stream relay: taskId={}", taskId, e);
            taskService.failTask(taskId, null, event.getProviderConfigString("geminiSessionId"), e.getMessage());
        }
    }

    public void reconnectTask(String taskId, String sessionId, String workerId) {
        GeminiTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
        if (entity == null || entity.getWorkerTaskId() == null || entity.getWorkerTaskId().isBlank()) {
            return;
        }
        try {
            GeminiWorkerClient client = getGeminiClient(workerId);
            int ackSeq = entity.getLastAckedSeq() != null ? entity.getLastAckedSeq() : 0;
            AtomicReference<String> detectedModel = new AtomicReference<>(entity.getModel());
            AtomicReference<String> detectedSessionId = new AtomicReference<>(entity.getGeminiSessionId());
            Disposable disposable = client.subscribeToTask(entity.getWorkerTaskId(), ackSeq).subscribe(
                    sse -> handleSseEvent(sse, taskId, sessionId, detectedModel, detectedSessionId),
                    error -> log.warn("Reconnect Gemini stream failed: taskId={}, error={}", taskId, error.getMessage()),
                    () -> activeStreams.remove(taskId)
            );
            activeStreams.put(taskId, disposable);
        } catch (Exception e) {
            log.warn("Failed to reconnect Gemini task {}: {}", taskId, e.getMessage());
        }
    }

    public void abortStream(String taskId) {
        Disposable disposable = activeStreams.remove(taskId);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        lastAckedSeq.remove(taskId);
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
        try {
            WorkerEvent event = objectMapper.readValue(data, WorkerEvent.class);
            Integer ackSeq = event.getSeq() != null ? event.getSeq() : 0;
            lastAckedSeq.computeIfAbsent(taskId, ignored -> new AtomicInteger()).set(ackSeq);

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

            taskService.recordWorkerProgress(taskId, event.getTaskId(), detectedSessionId.get(), detectedModel.get(), ackSeq);
            relayWorkerEvent(sessionId, taskId, event, detectedSessionId.get());
        } catch (Exception e) {
            log.warn("Failed to decode Gemini worker event: taskId={}, error={}", taskId, e.getMessage());
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

        switch (type) {
            case "assistant_text" -> {
                if ("sync_checkpoint".equals(event.getSubtype())) {
                    return;
                }
                publishBuilt(mb.textComplete(event.getContent() != null ? event.getContent() : ""));
            }
            case "tool_use" -> publishBuilt(mb.toolCallStart(event.getToolUseId(), event.getTool(), event.getInput()));
            case "tool_result" -> {
                boolean success = event.getIsError() == null || !event.getIsError();
                publishBuilt(mb.toolCallResult(event.getToolUseId(), event.getTool(), event.getOutput(), success));
            }
            case "result" -> {
                String resultText = firstNonBlank(event.getContent(), event.getResult());
                mb.result(resultText)
                        .metrics(event.getCostUsd(), event.getDurationMs(),
                                event.getInputTokens(), event.getOutputTokens(),
                                event.getNumTurns(), event.getModel());
                publishEvent(mb.build(MessageType.SESSION_END));
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
                activeStreams.remove(taskId);
            }
            case "error" -> {
                String errorText = firstNonBlank(event.getError(), "Unknown Gemini worker error");
                publishBuilt(mb.error(errorText));
                taskService.failTask(taskId, event.getTaskId(), geminiSessionId, errorText);
                activeStreams.remove(taskId);
            }
            default -> log.debug("Unhandled Gemini event type: {}", type);
        }
    }

    private void publishMessage(String sessionId, MessageType type, Map<String, Object> payload) {
        eventPublisher.publishEvent(AgentMessage.of(sessionId, GeminiTaskService.AGENT_ID, type, payload));
    }

    private void publishBuilt(AgentMessageBuilder builder) {
        eventPublisher.publishEvent(builder.build());
    }

    private void publishEvent(AgentMessage message) {
        eventPublisher.publishEvent(message);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
