package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.event.TaskStartedEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.AgentMessageBuilder;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.model.event.CodexTaskStartEvent;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Codex Worker SSE → AgentMessage 桥接
 *
 * 监听 CodexTaskStartEvent，通过 WebClient 消费 Worker SSE 流，
 * 将每个 Worker 事件转为 AgentMessage 并 publishEvent，
 * 后续由现有 SessionEventListener 处理持久化和 SSE 推送。
 *
 * 复用 Claude Worker 的 WorkerEvent 格式（Codex Worker 输出兼容格式）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodexStreamRelay {

    private static final String AGENT_ID = "codex-worker";
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_BASE_DELAY_MS = 2000;

    private final ClaudeWorkerFacade claudeWorkerFacade;
    private final CodexWorkerClientFactory clientFactory;
    private final CodexTaskService taskService;
    private final CodexTaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** 活跃的流订阅，用于 abort */
    private final ConcurrentHashMap<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    /** 每个任务已确认接收的最新事件序列号（ESN） */
    private final ConcurrentHashMap<String, AtomicInteger> lastAckedSeq = new ConcurrentHashMap<>();

    /** 重连互斥锁 */
    private final ConcurrentHashMap<String, AtomicBoolean> reconnecting = new ConcurrentHashMap<>();

    @Async("sessionEventExecutor")
    @EventListener
    public void onTaskStart(CodexTaskStartEvent event) {
        String taskId = event.getTaskId();
        String sessionId = event.getSessionId();
        String workerId = event.getWorkerId();

        log.info("Starting Codex stream relay: taskId={}, sessionId={}, workerId={}", taskId, sessionId, workerId);

        // 发送 SESSION_START
        publishMessage(sessionId, MessageType.SESSION_START,
                Map.of("content", "Connecting to Codex worker...", "taskId", taskId));

        try {
            CodexWorkerClient client = getCodexClient(workerId);

            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedCodexThreadId = new AtomicReference<>(event.getCodexThreadId());

            Flux<ServerSentEvent<String>> sseFlux = client.streamQuery(
                    event.getPrompt(), event.getCwd(),
                    event.getCodexThreadId(), event.getModel(),
                    event.getMaxTurns(), event.getApiKey());

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId,
                    detectedModel, detectedCodexThreadId, 0);

            activeStreams.put(taskId, subscription);

            // 发布跨 Agent 任务开始事件
            eventPublisher.publishEvent(TaskStartedEvent.builder()
                    .externalTaskId(taskId)
                    .parentSessionId(sessionId)
                    .targetAgentId(AGENT_ID)
                    .prompt(truncateResult(event.getPrompt()))
                    .build());

        } catch (Exception e) {
            log.error("Failed to start Codex stream relay: taskId={}", taskId, e);
            taskService.failTask(taskId, null, null, e.getMessage());
            publishMessage(sessionId, MessageType.ERROR,
                    Map.of("content", "Failed to connect to Codex worker: " + e.getMessage(), "taskId", taskId));
        }
    }

    /**
     * 重连已在 Worker 上运行的任务
     */
    public void reconnectTask(String taskId, String sessionId, String workerId) {
        if (activeStreams.containsKey(taskId)) {
            log.debug("reconnectTask: task {} already has active stream, skipping", taskId);
            return;
        }

        AtomicBoolean guard = reconnecting.computeIfAbsent(taskId, k -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            log.debug("reconnectTask: task {} reconnection already in progress, skipping", taskId);
            return;
        }

        log.info("Reconnecting Codex stream: taskId={}, sessionId={}, workerId={}", taskId, sessionId, workerId);

        try {
            CodexWorkerClient client = getCodexClient(workerId);
            CodexTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
            if (entity == null) {
                log.warn("reconnectTask: task {} not found in repository", taskId);
                return;
            }
            if (entity.getWorkerTaskId() == null || entity.getWorkerTaskId().isBlank()) {
                log.warn("reconnectTask: task {} has no upstream workerTaskId yet, skip reconnect", taskId);
                return;
            }

            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedCodexThreadId = new AtomicReference<>(entity.getCodexThreadId());

            AtomicInteger seqTracker = lastAckedSeq.get(taskId);
            int memoryAckSeq = seqTracker != null ? seqTracker.get() : 0;
            int persistedAckSeq = entity.getLastAckedSeq() != null ? entity.getLastAckedSeq() : 0;
            int ackSeq = Math.max(memoryAckSeq, persistedAckSeq);

            Flux<ServerSentEvent<String>> sseFlux = client.subscribeToTask(entity.getWorkerTaskId(), ackSeq);

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId,
                    detectedModel, detectedCodexThreadId, 0);

            activeStreams.put(taskId, subscription);

        } catch (Exception e) {
            log.warn("Failed to reconnect Codex task {}: {}", taskId, e.getMessage());
        } finally {
            guard.set(false);
        }
    }

    /**
     * 启动时重连所有活跃任务
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            Thread.sleep(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        List<CodexTaskEntity> activeTasks = taskRepository.findByStatusIn(List.of("RUNNING"));
        if (activeTasks.isEmpty()) {
            log.info("No active Codex tasks to reconnect on startup");
            return;
        }

        log.info("Attempting to reconnect {} active Codex task(s) on startup", activeTasks.size());
        for (CodexTaskEntity task : activeTasks) {
            try {
                reconnectTask(task.getTaskId(), task.getSessionId(), task.getWorkerId());
            } catch (Exception e) {
                log.warn("Failed to reconnect Codex task {} on startup: {}", task.getTaskId(), e.getMessage());
            }
        }
    }

    /**
     * 中止流
     */
    public void abortStream(String taskId) {
        Disposable subscription = activeStreams.remove(taskId);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.info("Aborted Codex stream: taskId={}", taskId);
        }
        lastAckedSeq.remove(taskId);
        reconnecting.remove(taskId);
    }

    /**
     * 尝试远程中止 worker 侧任务；若尚未拿到 upstream task_id，仅记录日志。
     */
    public void abortRemoteTask(CodexTaskEntity task) {
        if (task == null || task.getWorkerTaskId() == null || task.getWorkerTaskId().isBlank()) {
            log.warn("abortRemoteTask skipped: no upstream workerTaskId for local task {}", task != null ? task.getTaskId() : null);
            return;
        }
        try {
            getCodexClient(task.getWorkerId())
                    .abortTask(task.getWorkerTaskId())
                    .block(Duration.ofSeconds(10));
            log.info("Requested upstream abort: localTaskId={}, workerTaskId={}",
                    task.getTaskId(), task.getWorkerTaskId());
        } catch (Exception e) {
            log.warn("Failed to abort upstream Codex task: localTaskId={}, workerTaskId={}, error={}",
                    task.getTaskId(), task.getWorkerTaskId(), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private Disposable subscribeSseFlux(Flux<ServerSentEvent<String>> sseFlux,
                                         String taskId, String sessionId, String workerId,
                                         AtomicReference<String> detectedModel,
                                         AtomicReference<String> detectedCodexThreadId,
                                         int reconnectAttempt) {
        return sseFlux.subscribe(
                sse -> handleSseEvent(sse, taskId, sessionId, detectedModel, detectedCodexThreadId),
                error -> {
                    log.warn("Codex SSE stream error: taskId={}, attempt={}, error={}",
                            taskId, reconnectAttempt, error.getMessage());
                    activeStreams.remove(taskId);

                    if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                        long delay = (long) Math.pow(2, reconnectAttempt) * RECONNECT_BASE_DELAY_MS;
                        log.info("Scheduling Codex stream reconnection in {}ms: taskId={}", delay, taskId);
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        reconnectTask(taskId, sessionId, workerId);
                    } else {
                        log.error("Max reconnection attempts reached for Codex task {}", taskId);
                        taskService.failTask(taskId, null, detectedCodexThreadId.get(),
                                "SSE stream disconnected after " + MAX_RECONNECT_ATTEMPTS + " reconnection attempts");
                        publishMessage(sessionId, MessageType.ERROR,
                                Map.of("content", "Connection to Codex worker lost", "taskId", taskId));
                    }
                },
                () -> {
                    log.info("Codex SSE stream completed: taskId={}", taskId);
                    activeStreams.remove(taskId);
                    lastAckedSeq.remove(taskId);
                    reconnecting.remove(taskId);
                }
        );
    }

    private void handleSseEvent(ServerSentEvent<String> sse, String taskId, String sessionId,
                                 AtomicReference<String> detectedModel,
                                 AtomicReference<String> detectedCodexThreadId) {
        String data = sse.data();
        if (data == null || data.isEmpty()) return;

        try {
            WorkerEvent event = objectMapper.readValue(data, WorkerEvent.class);

            // 更新 ESN
            if (event.getSeq() != null) {
                lastAckedSeq.computeIfAbsent(taskId, k -> new AtomicInteger(0))
                        .updateAndGet(current -> Math.max(current, event.getSeq()));
            }

            // 更新 codexThreadId
            if (event.getSessionId() != null) {
                detectedCodexThreadId.set(event.getSessionId());
            }

            // 更新 model
            if (event.getModel() != null) {
                detectedModel.set(event.getModel());
            }

            taskService.recordWorkerProgress(taskId, event.getTaskId(), event.getSessionId(),
                    event.getModel(), event.getSeq());

            String type = event.getType();
            if (type == null) return;

            // 使用 AgentMessageBuilder 标准化 payload 字段名
            AgentMessageBuilder mb = AgentMessageBuilder.create(sessionId, AGENT_ID).taskId(taskId);

            switch (type) {
                case "assistant_text" -> {
                    if ("sync_checkpoint".equals(event.getSubtype())) {
                        log.debug("Ignoring sync checkpoint for task {}", taskId);
                        return;
                    }
                    publishBuilt(mb.textComplete(event.getContent() != null ? event.getContent() : ""));
                }
                case "tool_use" -> {
                    // 标准化: Codex 原字段 tool/input → 统一 toolName/arguments
                    publishBuilt(mb.toolCallStart(event.getToolUseId(), event.getTool(), event.getInput()));
                }
                case "tool_result" -> {
                    // 标准化: Codex 原字段 tool/output/isError → 统一 toolName/data/success
                    boolean success = event.getIsError() == null || !event.getIsError();
                    publishBuilt(mb.toolCallResult(event.getToolUseId(), event.getTool(),
                            event.getOutput(), success));
                }
                case "result" -> {
                    String resultText = event.getContent() != null ? event.getContent() : event.getResult();
                    mb.result(resultText)
                            .metrics(event.getCostUsd(), event.getDurationMs(),
                                    event.getInputTokens(), event.getOutputTokens(),
                                    event.getNumTurns(), event.getModel());
                    // result 事件用 SESSION_END 类型（Codex 特有语义）
                    publishEvent(mb.build(MessageType.SESSION_END));

                    // 完成任务记录
                    taskService.completeTask(taskId, event.getTaskId(),
                            detectedCodexThreadId.get(), resultText, event.getCostUsd(),
                            event.getInputTokens(), event.getOutputTokens(), event.getDurationMs(),
                            event.getNumTurns(), event.getModel());

                    // 发布任务完成事件
                    eventPublisher.publishEvent(TaskCompletionEvent.builder()
                            .externalTaskId(taskId)
                            .parentSessionId(sessionId)
                            .targetAgentId(AGENT_ID)
                            .resultSummary(truncateResult(resultText))
                            .status("COMPLETED")
                            .build());
                }
                case "error" -> {
                    publishBuilt(mb.error(event.getError() != null ? event.getError() : "Unknown error"));
                    taskService.failTask(taskId, event.getTaskId(), detectedCodexThreadId.get(),
                            event.getError());

                    eventPublisher.publishEvent(TaskCompletionEvent.builder()
                            .externalTaskId(taskId)
                            .parentSessionId(sessionId)
                            .targetAgentId(AGENT_ID)
                            .resultSummary(event.getError())
                            .status("FAILED")
                            .build());
                }
                default -> log.debug("Unhandled Codex event type: {}", type);
            }

        } catch (Exception e) {
            log.warn("Failed to parse Codex SSE event: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private void publishMessage(String sessionId, MessageType type, Map<String, Object> payload) {
        AgentMessage message = AgentMessage.of(sessionId, AGENT_ID, type, payload);
        eventPublisher.publishEvent(message);
    }

    private void publishBuilt(AgentMessageBuilder builder) {
        eventPublisher.publishEvent(builder.build());
    }

    private void publishEvent(AgentMessage message) {
        eventPublisher.publishEvent(message);
    }

    private String truncateResult(String text) {
        if (text == null) return null;
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    /**
     * 通过 ClaudeWorkerFacade 获取 CodexConfig 并创建 Client
     */
    private CodexWorkerClient getCodexClient(String workerId) {
        CodexConfig config = claudeWorkerFacade.getCodexConfig(workerId);
        if (config == null || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalStateException("Codex not configured for worker: " + workerId);
        }
        return clientFactory.getOrCreate(workerId + ":codex", config.getBaseUrl(), config.getAuthToken());
    }
}
