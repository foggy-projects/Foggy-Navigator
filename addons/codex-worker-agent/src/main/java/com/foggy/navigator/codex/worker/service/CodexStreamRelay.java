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
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private static final String AGENT_ID = CodexTaskService.CODEX_PROVIDER_TYPE;
    private static final String CODEX_BIZ_AGENT_ID = CodexTaskService.CODEX_BIZ_PROVIDER_TYPE;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_BASE_DELAY_MS = 2000;

    private final WorkerManagementFacade workerManagementFacade;
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
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true,
            condition = "#event.providerType == 'codex-worker' || #event.providerType == 'codex-biz-worker'")
    public void onTaskStart(WorkerTaskStartEvent event) {
        String taskId = event.getTaskId();
        String sessionId = event.getSessionId();
        String workerId = event.getWorkerId();
        String providerType = providerType(event.getProviderType());

        log.info("Starting Codex stream relay: taskId={}, providerType={}, sessionId={}, workerId={}",
                taskId, providerType, sessionId, workerId);

        // 发送 SESSION_START
        Map<String, Object> sessionStartPayload = new LinkedHashMap<>();
        sessionStartPayload.put("content", "Connecting to Codex worker...");
        sessionStartPayload.put("taskId", taskId);
        if (event.getProviderConfigString("codexThreadId") != null) {
            sessionStartPayload.put("codexThreadId", event.getProviderConfigString("codexThreadId"));
        }
        publishMessage(sessionId, providerType, MessageType.SESSION_START, sessionStartPayload);

        try {
            CodexWorkerClient client = getCodexClient(workerId);

            String codexThreadId = event.getProviderConfigString("codexThreadId");
            String images = blankToNull(event.getProviderConfigString("images"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = event.getProviderConfigValue("attachments");
            String baseUrl = blankToNull(event.getProviderConfigString("baseUrl"));
            @SuppressWarnings("unchecked")
            Map<String, String> extraEnvVars = event.getProviderConfigValue("extraEnvVars");
            String codexHomeKey = blankToNull(event.getProviderConfigString("codexHomeKey"));
            String developerInstructions = blankToNull(event.getProviderConfigString("developerInstructions"));
            @SuppressWarnings("unchecked")
            Map<String, Object> businessRuntimeContext = event.getProviderConfigValue("businessRuntimeContext");
            @SuppressWarnings("unchecked")
            Map<String, Object> outputSchema = event.getProviderConfigValue("outputSchema");
            @SuppressWarnings("unchecked")
            Map<String, Object> codexConfig = event.getProviderConfigValue("codexConfig");
            String sandboxMode = blankToNull(event.getProviderConfigString("sandboxMode"));
            String approvalPolicy = blankToNull(event.getProviderConfigString("approvalPolicy"));
            Boolean networkAccessEnabled = event.getProviderConfigValue("networkAccessEnabled");
            String webSearchMode = blankToNull(event.getProviderConfigString("webSearchMode"));
            @SuppressWarnings("unchecked")
            List<String> additionalDirectories = event.getProviderConfigValue("additionalDirectories");
            log.info(
                    "Dispatching Codex worker query: taskId={}, workerId={}, model={}, hasApiKey={}, baseUrl={}, envVarKeys={}, hasImages={}, resumeThread={}, hasCodexHomeKey={}, sandboxMode={}, approvalPolicy={}",
                    taskId,
                    workerId,
                    event.getModel(),
                    event.getApiKey() != null && !event.getApiKey().isBlank(),
                    baseUrl,
                    extraEnvVars != null ? extraEnvVars.keySet() : List.of(),
                    images != null && !images.isBlank(),
                    codexThreadId != null && !codexThreadId.isBlank(),
                    codexHomeKey != null,
                    sandboxMode,
                    approvalPolicy
            );
            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedCodexThreadId = new AtomicReference<>(codexThreadId);

            Flux<ServerSentEvent<String>> sseFlux = client.streamQuery(
                    event.getPrompt(), event.getCwd(),
                    codexThreadId, event.getModel(),
                    event.getMaxTurns(), images, attachments, event.getApiKey(), baseUrl, extraEnvVars,
                    codexHomeKey, developerInstructions, outputSchema, codexConfig,
                    sandboxMode, approvalPolicy, networkAccessEnabled, webSearchMode,
                    businessRuntimeContext, additionalDirectories);

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId, providerType,
                    detectedModel, detectedCodexThreadId, 0);

            registerActiveStream(taskId, subscription);

            // 发布跨 Agent 任务开始事件
            eventPublisher.publishEvent(TaskStartedEvent.builder()
                    .externalTaskId(taskId)
                    .parentSessionId(sessionId)
                    .targetAgentId(providerType)
                    .prompt(truncateResult(event.getPrompt()))
                    .build());

        } catch (Exception e) {
            log.error("Failed to start Codex stream relay: taskId={}", taskId, e);
            taskService.failTask(taskId, null, null, connectionFailureMessage(e));
            publishMessage(sessionId, providerType, MessageType.ERROR,
                    Map.of("content", "Failed to connect to Codex worker: " + connectionFailureMessage(e), "taskId", taskId));
        }
    }

    /**
     * 重连已在 Worker 上运行的任务
     */
    public void reconnectTask(String taskId, String sessionId, String workerId) {
        reconnectTask(taskId, sessionId, workerId, 0);
    }

    private void reconnectTask(String taskId, String sessionId, String workerId, int reconnectAttempt) {
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
            String providerType = resolveTaskProviderType(taskId);

            AtomicInteger seqTracker = lastAckedSeq.get(taskId);
            int memoryAckSeq = seqTracker != null ? seqTracker.get() : 0;
            int persistedAckSeq = entity.getLastAckedSeq() != null ? entity.getLastAckedSeq() : 0;
            int ackSeq = Math.max(memoryAckSeq, persistedAckSeq);

            Flux<ServerSentEvent<String>> sseFlux = client.subscribeToTask(entity.getWorkerTaskId(), ackSeq);

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId, providerType,
                    detectedModel, detectedCodexThreadId, reconnectAttempt);

            registerActiveStream(taskId, subscription);

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
                                         String taskId, String sessionId, String workerId, String providerType,
                                         AtomicReference<String> detectedModel,
                                         AtomicReference<String> detectedCodexThreadId,
                                         int reconnectAttempt) {
        return sseFlux.subscribe(
                sse -> handleSseEvent(sse, taskId, sessionId, providerType, detectedModel, detectedCodexThreadId),
                error -> {
                    log.warn("Codex SSE stream error: taskId={}, attempt={}, error={}",
                            taskId, reconnectAttempt, error.getMessage());
                    activeStreams.remove(taskId);

                    if (!hasAcceptedWorkerTask(taskId)) {
                        failStreamTask(taskId, sessionId, providerType, detectedCodexThreadId,
                                "Codex worker stream failed before worker task was accepted: "
                                        + connectionFailureMessage(error));
                        return;
                    }

                    if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                        long delay = (long) Math.pow(2, reconnectAttempt) * RECONNECT_BASE_DELAY_MS;
                        log.info("Scheduling Codex stream reconnection in {}ms: taskId={}", delay, taskId);
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        reconnectTask(taskId, sessionId, workerId, reconnectAttempt + 1);
                    } else {
                        log.error("Max reconnection attempts reached for Codex task {}", taskId);
                        failStreamTask(taskId, sessionId, providerType, detectedCodexThreadId,
                                "SSE stream disconnected after " + MAX_RECONNECT_ATTEMPTS
                                        + " reconnection attempts: " + connectionFailureMessage(error));
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

    private void registerActiveStream(String taskId, Disposable subscription) {
        if (subscription != null && !subscription.isDisposed()) {
            activeStreams.put(taskId, subscription);
        }
    }

    private boolean hasAcceptedWorkerTask(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .map(CodexTaskEntity::getWorkerTaskId)
                .map(workerTaskId -> !workerTaskId.isBlank())
                .orElse(false);
    }

    private void failStreamTask(String taskId, String sessionId, String providerType,
                                AtomicReference<String> detectedCodexThreadId,
                                String errorMessage) {
        taskService.failTask(taskId, null, detectedCodexThreadId.get(), errorMessage);
        publishMessage(sessionId, providerType, MessageType.ERROR,
                Map.of("content", errorMessage, "taskId", taskId));
        lastAckedSeq.remove(taskId);
        reconnecting.remove(taskId);
        eventPublisher.publishEvent(TaskCompletionEvent.builder()
                .externalTaskId(taskId)
                .parentSessionId(sessionId)
                .targetAgentId(providerType)
                .resultSummary(truncateResult(errorMessage))
                .status("FAILED")
                .build());
    }

    private String connectionFailureMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "unknown error";
        }
        return error.getMessage();
    }

    private void handleSseEvent(ServerSentEvent<String> sse, String taskId, String sessionId, String providerType,
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
                    event.getModel(), event.getSeq(), isUserVisibleOutputEvent(event));

            String type = event.getType();
            if (type == null) return;

            // 使用 AgentMessageBuilder 标准化 payload 字段名
            AgentMessageBuilder mb = AgentMessageBuilder.create(sessionId, providerType)
                    .taskId(taskId)
                    .put("codexThreadId", detectedCodexThreadId.get());

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
                            .targetAgentId(providerType)
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
                            .targetAgentId(providerType)
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

    private void publishMessage(String sessionId, String providerType, MessageType type, Map<String, Object> payload) {
        AgentMessage message = AgentMessage.of(sessionId, providerType, type, payload);
        eventPublisher.publishEvent(message);
    }

    private String resolveTaskProviderType(String taskId) {
        try {
            if (taskService.getTaskByIdForProvider(taskId, CODEX_BIZ_AGENT_ID).isPresent()) {
                return CODEX_BIZ_AGENT_ID;
            }
        } catch (Exception e) {
            log.debug("Failed to resolve Codex task providerType: taskId={}, error={}", taskId, e.getMessage());
        }
        return AGENT_ID;
    }

    private String providerType(String providerType) {
        return CODEX_BIZ_AGENT_ID.equals(providerType) ? CODEX_BIZ_AGENT_ID : AGENT_ID;
    }

    private void publishBuilt(AgentMessageBuilder builder) {
        eventPublisher.publishEvent(builder.build());
    }

    private void publishEvent(AgentMessage message) {
        eventPublisher.publishEvent(message);
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

    private String truncateResult(String text) {
        if (text == null) return null;
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    /**
     * 通过 WorkerManagementFacade 获取 CodexConfig 并创建 Client
     */
    private CodexWorkerClient getCodexClient(String workerId) {
        CodexConfig config = workerManagementFacade.getCodexConfig(workerId);
        if (config == null || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalStateException("Codex not configured for worker: " + workerId);
        }
        return clientFactory.getOrCreate(workerId + ":codex", config.getBaseUrl(), config.getAuthToken());
    }
}
