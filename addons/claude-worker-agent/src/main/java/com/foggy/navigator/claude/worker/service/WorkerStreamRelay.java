package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.event.TaskStartedEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.event.ClaudeTaskStartEvent;
import com.foggy.navigator.claude.worker.model.event.WorkerEvent;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Worker SSE → AgentMessage 桥接
 *
 * 监听 ClaudeTaskStartEvent，通过 WebClient 消费 Worker SSE 流，
 * 将每个 Worker 事件转为 AgentMessage 并 publishEvent，
 * 后续由现有 SessionEventListener 处理持久化和 SSE 推送。
 *
 * 支持 Java 重启后自动重连 Worker 上仍存活的任务流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerStreamRelay {

    private static final String AGENT_ID = "claude-worker";

    /** 启动后延迟多少毫秒再尝试重连（等 Worker 健康检查完成） */
    private static final long STARTUP_RECONNECT_DELAY_MS = 10_000;

    /** SSE 断连后最大重连次数 */
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    /** 重连退避基础延迟（毫秒），实际延迟 = 2^attempt * BASE */
    private static final long RECONNECT_BASE_DELAY_MS = 2000;

    private final ClaudeWorkerService workerService;
    private final ClaudeTaskService taskService;
    private final ClaudeTaskRepository taskRepository;
    private final ConversationConfigService conversationConfigService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** 活跃的流订阅，用于 abort */
    private final ConcurrentHashMap<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    /** Java taskId → Worker 内部 taskId 映射 (worker 生成的 UUID) */
    private final ConcurrentHashMap<String, String> workerTaskIdMap = new ConcurrentHashMap<>();

    /**
     * 每个任务已确认接收的最新事件序列号（ESN）。
     * 用于 SSE 断连重连时通过 ack_seq 参数精确回放遗漏事件。
     * Java 重启后此 Map 为空，此时 ack_seq=0（从头回放，幂等安全）。
     *
     * 与旧的 receivedEventCounts（事件计数）相比，ESN 基于 Worker 注入的
     * 单调递增序列号，不受 relay 失败、解析异常等影响，更加可靠。
     * 当 Worker 未返回 seq 字段时（旧版 Worker），降级为计数模式。
     */
    private final ConcurrentHashMap<String, AtomicInteger> lastAckedSeq = new ConcurrentHashMap<>();

    /** 每个任务的重连互斥锁，防止 doOnComplete/doOnError/Reconciler 并发触发多次重连 */
    private final ConcurrentHashMap<String, AtomicBoolean> reconnecting = new ConcurrentHashMap<>();

    @Async("sessionEventExecutor")
    @EventListener
    public void onTaskStart(ClaudeTaskStartEvent event) {
        String taskId = event.getTaskId();
        String sessionId = event.getSessionId();
        String workerId = event.getWorkerId();

        log.info("Starting stream relay: taskId={}, sessionId={}, workerId={}", taskId, sessionId, workerId);

        // 发送 SESSION_START
        publishMessage(sessionId, MessageType.SESSION_START,
                Map.of("content", "Connecting to worker...", "taskId", taskId));

        try {
            ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
            ClaudeWorkerClient client = workerService.createClient(worker);

            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedClaudeSessionId = new AtomicReference<>(event.getClaudeSessionId());

            Flux<ServerSentEvent<String>> sseFlux = client.streamQuery(event.getPrompt(), event.getCwd(),
                    event.getClaudeSessionId(), event.getModel(), event.getMaxTurns(),
                    event.getAgentTeamsJson(), event.getImages(),
                    event.getApiKey(), event.getAuthToken(), event.getBaseUrl(),
                    event.getPermissionMode(), event.getNavigatorApiKey(),
                    event.getTaskId(), event.getSessionId(),
                    event.getExtraEnvVars());

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId,
                    detectedModel, detectedClaudeSessionId, 0);

            activeStreams.put(taskId, subscription);

            // 发布跨 Agent 任务开始事件
            eventPublisher.publishEvent(TaskStartedEvent.builder()
                    .externalTaskId(taskId)
                    .parentSessionId(sessionId)
                    .targetAgentId(AGENT_ID)
                    .prompt(truncateResult(event.getPrompt()))
                    .build());

        } catch (Exception e) {
            log.error("Failed to start stream relay: taskId={}", taskId, e);
            taskService.failTask(taskId, null, e.getMessage());
            publishMessage(sessionId, MessageType.ERROR,
                    Map.of("content", "Failed to connect to worker: " + e.getMessage(), "taskId", taskId));
        }
    }

    // -------------------------------------------------------------------------
    // Reconnection support
    // -------------------------------------------------------------------------

    /**
     * 重连一个仍在 Worker 上运行的任务。
     * 用于 Java 重启后或 SSE 断线后恢复流式事件消费。
     * 不会发布 SESSION_START 或 TaskStartedEvent（任务已经在进行中）。
     */
    public void reconnectTask(String taskId, String sessionId, String workerId) {
        if (activeStreams.containsKey(taskId)) {
            log.debug("reconnectTask: task {} already has active stream, skipping", taskId);
            return;
        }

        // 互斥锁：防止 doOnComplete/doOnError/Reconciler/启动重连 并发触发多次重连
        AtomicBoolean guard = reconnecting.computeIfAbsent(taskId, k -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            log.debug("reconnectTask: task {} reconnection already in progress, skipping", taskId);
            return;
        }

        log.info("Reconnecting stream: taskId={}, sessionId={}, workerId={}", taskId, sessionId, workerId);

        try {
            ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
            ClaudeWorkerClient client = workerService.createClient(worker);

            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedClaudeSessionId = new AtomicReference<>();

            // 用 lastAckedSeq（ESN）计算 ack_seq：
            // - 有 seq → 精确回放遗漏的事件（Worker 返回 seq > ack_seq 的所有事件）
            // - 无 seq（Java 重启） → ack_seq=0，从头回放（安全，幂等）
            AtomicInteger seqTracker = lastAckedSeq.get(taskId);
            int ackSeq = seqTracker != null ? seqTracker.get() : 0;
            log.info("reconnectTask ack_seq={} for task {} (tracker={})", ackSeq, taskId, seqTracker);
            Flux<ServerSentEvent<String>> sseFlux = client.subscribeToTask(taskId, ackSeq);

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId,
                    detectedModel, detectedClaudeSessionId, 0);

            activeStreams.put(taskId, subscription);

            // Reset interactionState to PROCESSING — the previous SSE disconnect
            // may have left it as AWAITING_REPLY via failIfStillRunning path.
            conversationConfigService.updateInteractionState(sessionId, "PROCESSING");

            // Push a STATE_SYNC so the frontend knows the task stream is reconnected
            publishMessage(sessionId, MessageType.STATE_SYNC,
                    Map.of("content", "Task stream reconnected", "subtype", "reconnected", "taskId", taskId));

        } catch (Exception e) {
            log.warn("Failed to reconnect task {}: {}", taskId, e.getMessage());
            // Don't fail the task — Reconciler will handle it if CLI is truly dead.
        } finally {
            guard.set(false);
        }
    }

    /**
     * 启动时重连所有仍在 RUNNING / AWAITING_PERMISSION 状态的任务。
     */
    @Async("sessionEventExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            Thread.sleep(STARTUP_RECONNECT_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        List<String> activeStatuses = List.of("RUNNING", "AWAITING_PERMISSION");
        List<ClaudeTaskEntity> activeTasks = taskRepository.findByStatusIn(activeStatuses);

        if (activeTasks.isEmpty()) {
            log.info("Startup reconnect: no active tasks found");
            return;
        }

        log.info("Startup reconnect: found {} active task(s), attempting reconnection...", activeTasks.size());

        for (ClaudeTaskEntity task : activeTasks) {
            try {
                // Java 重启后 lastAckedSeq 为空 → 先查 Worker 状态判断是否需要回放
                try {
                    ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
                    ClaudeWorkerClient client = workerService.createClient(worker);
                    Map<String, Object> status = client.getTaskStatus(task.getTaskId())
                            .block(java.time.Duration.ofSeconds(5));
                    if (status != null) {
                        boolean closed = Boolean.TRUE.equals(status.get("closed"));
                        int latestSeq = ((Number) status.getOrDefault("latest_seq", 0)).intValue();
                        log.info("Startup reconnect: task {} Worker status: closed={}, latestSeq={}",
                                task.getTaskId(), closed, latestSeq);
                        // ackSeq=0 会让 Worker 从头回放所有事件（安全，幂等）
                        // 即使 Worker 已 closed，回放仍能让 Java 收到 result + sync_checkpoint
                    }
                } catch (Exception e) {
                    log.debug("Startup reconnect: cannot query Worker status for {}: {}",
                            task.getTaskId(), e.getMessage());
                }

                reconnectTask(task.getTaskId(), task.getSessionId(), task.getWorkerId());
            } catch (Exception e) {
                log.warn("Startup reconnect failed for task {}: {}", task.getTaskId(), e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Common SSE subscription wiring
    // -------------------------------------------------------------------------

    /**
     * 创建 SSE Flux 的通用订阅逻辑（新任务和重连共用）。
     *
     * @param reconnectAttempt 当前是第几次重连（0=首次连接），
     *                         超过 MAX_RECONNECT_ATTEMPTS 后不再重试。
     */
    private Disposable subscribeSseFlux(Flux<ServerSentEvent<String>> sseFlux,
                                         String taskId, String sessionId, String workerId,
                                         AtomicReference<String> detectedModel,
                                         AtomicReference<String> detectedClaudeSessionId,
                                         int reconnectAttempt) {
        return sseFlux
                .doOnNext(sse -> {
                    String data = sse.data();
                    if (data == null || data.isEmpty()) {
                        log.debug("Received empty SSE data for task: {}", taskId);
                        return;
                    }

                    try {
                        log.debug("Task {} received SSE data: {}", taskId, data.substring(0, Math.min(200, data.length())));
                        WorkerEvent workerEvent = objectMapper.readValue(data, WorkerEvent.class);
                        log.debug("Task {} parsed event type: {}", taskId, workerEvent.getType());
                        // Capture worker's internal task ID for abort/respond calls
                        if (workerEvent.getTaskId() != null && !workerTaskIdMap.containsKey(taskId)) {
                            workerTaskIdMap.put(taskId, workerEvent.getTaskId());
                            log.debug("Task {} mapped to worker task: {}", taskId, workerEvent.getTaskId());
                        }
                        try {
                            relayEvent(sessionId, taskId, workerEvent, detectedModel, detectedClaudeSessionId);
                        } catch (Exception relayEx) {
                            log.warn("Failed to relay event for task {}, type={}: {}",
                                    taskId, workerEvent.getType(), relayEx.getMessage(), relayEx);
                        }
                        // 更新 ACK 序列号：优先用 Worker 注入的 seq（ESN 模式），
                        // 若 Worker 未返回 seq（旧版），降级为单调递增计数
                        if (workerEvent.getSeq() != null) {
                            lastAckedSeq.computeIfAbsent(taskId, k -> new AtomicInteger(0))
                                    .updateAndGet(cur -> Math.max(cur, workerEvent.getSeq()));
                        } else {
                            // 旧 Worker 兼容：无 seq 字段，按计数递增
                            lastAckedSeq.computeIfAbsent(taskId, k -> new AtomicInteger(0)).incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse worker event for task {}: {}", taskId, data, e);
                    }
                })
                .doOnComplete(() -> {
                    log.info("Worker stream completed: taskId={}, reconnectAttempt={}/{}", taskId, reconnectAttempt, MAX_RECONNECT_ATTEMPTS);
                    activeStreams.remove(taskId);

                    if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                        boolean reconnected = attemptReconnect(taskId, sessionId, workerId, reconnectAttempt);
                        if (reconnected) {
                            return;
                        }
                    }

                    // 如果流正常结束但任务仍在 RUNNING（没收到 result/error 事件），
                    // 说明 CLI 可能因资源不足未能启动，或 Worker 异常断开。
                    // 对于 AWAITING_PERMISSION 任务，SSE 空闲期间连接易超时断开，
                    // 但 CLI 进程通常仍存活等待用户输入 — 交给 Reconciler 判断。
                    taskService.failIfStillRunning(taskId, sessionId,
                            "Worker 连接已断开，但未收到任务结果。可能原因：系统资源不足导致 CLI 无法启动，或 Worker 进程异常退出。");
                    lastAckedSeq.remove(taskId);
                })
                .doOnError(e -> {
                    log.error("Worker stream error: taskId={}", taskId, e);
                    activeStreams.remove(taskId);

                    // 4xx client errors (e.g. 429 Too Many Requests) are not transient —
                    // reconnecting won't help, fail immediately with a clear message.
                    boolean isClientError = e instanceof WebClientResponseException wce
                            && wce.getStatusCode().is4xxClientError();

                    if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS && !isClientError) {
                        boolean reconnected = attemptReconnect(taskId, sessionId, workerId, reconnectAttempt);
                        if (reconnected) {
                            return;
                        }
                    }

                    String errorMsg = extractWorkerErrorMessage(e);
                    taskService.failTask(taskId, detectedClaudeSessionId.get(), errorMsg);
                    publishMessage(sessionId, MessageType.ERROR,
                            Map.of("content", errorMsg, "taskId", taskId));
                    lastAckedSeq.remove(taskId);
                })
                .subscribe();
    }

    /**
     * 尝试通过 Worker subscribe 端点重连，带指数退避。
     * 仅当任务仍在 RUNNING 或 AWAITING_PERMISSION 时尝试。
     *
     * @param currentAttempt 当前重连次数（0-based），下次将传 currentAttempt+1
     * @return true 如果重连成功启动
     */
    private boolean attemptReconnect(String taskId, String sessionId, String workerId, int currentAttempt) {
        // 互斥锁：防止并发重连
        AtomicBoolean guard = reconnecting.computeIfAbsent(taskId, k -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            log.debug("attemptReconnect: task {} reconnection already in progress, skipping", taskId);
            return false;
        }
        try {
            // Check if the task is still active in DB
            var taskOpt = taskRepository.findByTaskId(taskId);
            if (taskOpt.isEmpty()) return false;
            String status = taskOpt.get().getStatus();
            if (!"RUNNING".equals(status) && !"AWAITING_PERMISSION".equals(status)) {
                return false;
            }

            int nextAttempt = currentAttempt + 1;
            long delayMs = (long) Math.pow(2, currentAttempt) * RECONNECT_BASE_DELAY_MS;
            log.info("Attempting SSE reconnection (attempt {}/{}) for task {} (status={}), backoff={}ms",
                    nextAttempt, MAX_RECONNECT_ATTEMPTS, taskId, status, delayMs);

            Thread.sleep(delayMs);

            ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
            ClaudeWorkerClient client = workerService.createClient(worker);

            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedClaudeSessionId = new AtomicReference<>();

            // 用 lastAckedSeq（ESN）计算 ack_seq
            AtomicInteger seqTracker = lastAckedSeq.get(taskId);
            int ackSeq = seqTracker != null ? seqTracker.get() : 0;
            log.info("attemptReconnect ack_seq={} for task {} (tracker={})", ackSeq, taskId, seqTracker);
            Flux<ServerSentEvent<String>> sseFlux = client.subscribeToTask(taskId, ackSeq);

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId,
                    detectedModel, detectedClaudeSessionId, nextAttempt);

            activeStreams.put(taskId, subscription);

            // Reset interactionState to PROCESSING
            conversationConfigService.updateInteractionState(sessionId, "PROCESSING");

            publishMessage(sessionId, MessageType.STATE_SYNC,
                    Map.of("content", "Task stream reconnected", "subtype", "reconnected", "taskId", taskId));

            log.info("SSE reconnection successful for task {} (attempt {}/{})", taskId, nextAttempt, MAX_RECONNECT_ATTEMPTS);
            return true;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("SSE reconnection interrupted for task {}", taskId);
            return false;
        } catch (Exception e) {
            log.warn("SSE reconnection failed for task {} (attempt {}/{}): {}",
                    taskId, currentAttempt + 1, MAX_RECONNECT_ATTEMPTS, e.getMessage());
            return false;
        } finally {
            guard.set(false);
        }
    }

    /**
     * 获取 Worker 内部 task ID (用于 abort/respond 调用)
     * @param javaTaskId Java 侧的 task ID
     * @return Worker 生成的 UUID task ID, 如果未映射则返回 javaTaskId
     */
    public String getWorkerTaskId(String javaTaskId) {
        return workerTaskIdMap.getOrDefault(javaTaskId, javaTaskId);
    }

    /**
     * 中止任务的流
     */
    public void abortStream(String taskId) {
        reconnecting.remove(taskId);
        Disposable subscription = activeStreams.remove(taskId);
        workerTaskIdMap.remove(taskId);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.info("Stream aborted: taskId={}", taskId);
        }
    }

    /**
     * 将 Worker 事件转为 AgentMessage 并发布
     */
    private void relayEvent(String sessionId, String taskId, WorkerEvent event,
                            AtomicReference<String> detectedModel,
                            AtomicReference<String> detectedClaudeSessionId) {
        if (event.getType() == null) return;

        switch (event.getType()) {
            case "system" -> {
                // 系统消息，携带 session_id 等元数据 — 尽早记住 claudeSessionId
                if (event.getSessionId() != null) {
                    detectedClaudeSessionId.set(event.getSessionId());
                    // 立即保存到数据库，防止任何情况下丢失（包括用户中止任务）
                    try {
                        taskService.updateClaudeSessionId(taskId, event.getSessionId());
                        log.debug("Early saved claudeSessionId {} for task {}", event.getSessionId(), taskId);
                    } catch (Exception e) {
                        log.warn("Failed to early save claudeSessionId for task {}: {}", taskId, e.getMessage());
                    }
                }
                String subtype = event.getSubtype();
                if ("auto_compact".equals(subtype) || "context_compression".equals(subtype)) {
                    // Context compression event — push as STATE_SYNC hint
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("content", "Context compressed");
                    payload.put("subtype", subtype);
                    payload.put("taskId", taskId);
                    publishMessage(sessionId, MessageType.STATE_SYNC, payload);
                } else if ("waiting".equals(subtype)) {
                    // Waiting hint — CLI is likely retrying internally
                    Map<String, Object> data = event.getData() != null ? event.getData() : Map.of();
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("content", "Waiting for response...");
                    payload.put("subtype", "waiting");
                    payload.put("elapsedSeconds", data.getOrDefault("elapsed_seconds", 0));
                    payload.put("timeoutSeconds", data.getOrDefault("timeout_seconds", 600));
                    payload.put("taskId", taskId);
                    publishMessage(sessionId, MessageType.STATE_SYNC, payload);
                } else {
                    publishMessage(sessionId, MessageType.SESSION_START,
                            Map.of("content", "Task started", "taskId", taskId,
                                    "claudeSessionId", nullSafe(event.getSessionId())));
                }
            }
            case "assistant_text" -> {
                // Track model from assistant_text events
                if (event.getModel() != null) {
                    detectedModel.set(event.getModel());
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("content", event.getContent());
                payload.put("taskId", taskId);
                // assistant_text from Python Worker is a complete text block (not a streaming chunk),
                // so emit TEXT_COMPLETE to ensure it gets persisted to the database.
                publishMessage(sessionId, MessageType.TEXT_COMPLETE, payload);
            }
            case "tool_use" -> {
                String toolCallId = event.getToolUseId() != null
                        ? event.getToolUseId() : "tc-" + System.nanoTime();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolCallId", toolCallId);
                payload.put("toolName", event.getTool());
                payload.put("arguments", event.getInput());
                payload.put("taskId", taskId);
                publishMessage(sessionId, MessageType.TOOL_CALL_START, payload);
            }
            case "tool_result" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolCallId", event.getToolUseId());
                payload.put("toolName", event.getTool());
                payload.put("data", event.getOutput());
                payload.put("success", !Boolean.TRUE.equals(event.getIsError()));
                payload.put("taskId", taskId);
                publishMessage(sessionId, MessageType.TOOL_CALL_RESULT, payload);
            }
            case "result" -> {
                // 任务完成
                // Worker 返回的 result 事件使用 content 字段，不是 result 字段
                String resultContent = event.getContent() != null ? event.getContent() : event.getResult();
                String resolvedModel = event.getModel() != null ? event.getModel() : detectedModel.get();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("content", resultContent);
                payload.put("taskId", taskId);
                payload.put("costUsd", event.getCostUsd());
                payload.put("durationMs", event.getDurationMs());
                payload.put("inputTokens", event.getInputTokens());
                payload.put("outputTokens", event.getOutputTokens());
                payload.put("numTurns", event.getNumTurns());
                payload.put("model", resolvedModel);
                payload.put("claudeSessionId", nullSafe(event.getSessionId()));
                publishMessage(sessionId, MessageType.TEXT_COMPLETE, payload);

                // 更新任务状态
                taskService.completeTask(taskId, event.getSessionId(),
                        event.getCostUsd(), event.getInputTokens(),
                        event.getOutputTokens(), event.getDurationMs(),
                        event.getNumTurns(), resolvedModel);
                activeStreams.remove(taskId);
                workerTaskIdMap.remove(taskId);
                lastAckedSeq.remove(taskId);

                // 发布跨 Agent 任务完成事件
                // 注意：Python event_mapper 将 result 文本放在 content 字段，
                // event.getResult() 始终为 null，应使用已解析的 resultContent
                eventPublisher.publishEvent(TaskCompletionEvent.builder()
                        .externalTaskId(taskId)
                        .parentSessionId(sessionId)
                        .targetAgentId(AGENT_ID)
                        .status("COMPLETED")
                        .resultSummary(truncateResult(resultContent))
                        .build());
            }
            case "permission_request" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("permissionId", event.getPermissionId());
                payload.put("toolName", event.getTool());
                payload.put("toolInput", event.getInput());
                payload.put("taskId", taskId);
                publishMessage(sessionId, MessageType.CONFIRMATION_REQUEST, payload);
                taskService.setAwaitingPermission(taskId);
            }
            case "user_question" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("permissionId", event.getPermissionId());
                payload.put("questions", event.getQuestions());
                payload.put("taskId", taskId);
                publishMessage(sessionId, MessageType.CONFIRMATION_REQUEST, payload);
                taskService.setAwaitingPermission(taskId);
            }
            case "plan_review" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("permissionId", event.getPermissionId());
                payload.put("planReview", true);
                if (event.getAllowedPrompts() != null) {
                    payload.put("allowedPrompts", event.getAllowedPrompts());
                }
                if (event.getPlan() != null) {
                    payload.put("plan", event.getPlan());
                }
                payload.put("taskId", taskId);
                publishMessage(sessionId, MessageType.CONFIRMATION_REQUEST, payload);
                taskService.setAwaitingPermission(taskId);
            }
            case "checkpoint" -> {
                String checkpointId = event.getCheckpointId();
                if (checkpointId != null && !checkpointId.isEmpty()) {
                    taskService.addCheckpoint(taskId, checkpointId);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("checkpointId", checkpointId);
                    payload.put("taskId", taskId);
                    publishMessage(sessionId, MessageType.CHECKPOINT, payload);
                }
            }
            case "error" -> {
                // 错误事件也可能携带 session_id
                if (event.getSessionId() != null) {
                    detectedClaudeSessionId.set(event.getSessionId());
                    // 立即保存到数据库，防止任何情况下丢失
                    try {
                        taskService.updateClaudeSessionId(taskId, event.getSessionId());
                        log.debug("Early saved claudeSessionId {} for task {} (error event)", event.getSessionId(), taskId);
                    } catch (Exception e) {
                        log.warn("Failed to early save claudeSessionId for task {} (error): {}", taskId, e.getMessage());
                    }
                }
                String errorClaudeSessionId = detectedClaudeSessionId.get();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("content", event.getError());
                payload.put("taskId", taskId);
                payload.put("claudeSessionId", nullSafe(errorClaudeSessionId));
                publishMessage(sessionId, MessageType.ERROR, payload);

                taskService.failTask(taskId, errorClaudeSessionId, event.getError());
                activeStreams.remove(taskId);
                workerTaskIdMap.remove(taskId);
                lastAckedSeq.remove(taskId);

                // 发布跨 Agent 任务失败事件
                eventPublisher.publishEvent(TaskCompletionEvent.builder()
                        .externalTaskId(taskId)
                        .parentSessionId(sessionId)
                        .targetAgentId(AGENT_ID)
                        .status("FAILED")
                        .resultSummary(event.getError())
                        .build());
            }
            case "sync_checkpoint" -> {
                // Worker 在 result/error 事件后发送 sync_checkpoint，
                // 携带 latest_seq 和 event_count，Java 可用于校验完整性
                Integer workerLatestSeq = event.getLatestSeq();
                Integer workerEventCount = event.getEventCount();
                AtomicInteger mySeq = lastAckedSeq.get(taskId);
                int myAckedSeq = mySeq != null ? mySeq.get() : 0;

                log.info("Sync checkpoint: taskId={}, workerLatestSeq={}, workerEventCount={}, myAckedSeq={}",
                        taskId, workerLatestSeq, workerEventCount, myAckedSeq);

                // 推送 STATE_SYNC 到前端，表明流已通过完整性校验
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("content", "Event stream verified");
                payload.put("subtype", "sync_checkpoint");
                payload.put("taskId", taskId);
                if (workerLatestSeq != null) payload.put("latestSeq", workerLatestSeq);
                publishMessage(sessionId, MessageType.STATE_SYNC, payload);
            }
            default -> log.debug("Unknown worker event type: {}", event.getType());
        }
    }

    // ---- Reconciler 查询接口 ----

    /** 获取指定任务的最后确认序列号，供 Reconciler 做 seq gap 检测 */
    public AtomicInteger getLastAckedSeq(String taskId) {
        return lastAckedSeq.get(taskId);
    }

    /** 检查指定任务是否有活跃的 SSE 流 */
    public boolean hasActiveStream(String taskId) {
        Disposable d = activeStreams.get(taskId);
        return d != null && !d.isDisposed();
    }

    /**
     * 从 WebClient 异常中提取用户友好的错误消息。
     * 对于 Worker 返回的 HTTP 错误（如 429），解析 FastAPI 的 JSON 响应体提取 detail 字段。
     */
    private String extractWorkerErrorMessage(Throwable e) {
        if (e instanceof WebClientResponseException wce) {
            int status = wce.getStatusCode().value();
            String body = wce.getResponseBodyAsString();
            // FastAPI returns {"detail": "..."} for HTTPException
            if (body != null && !body.isBlank()) {
                try {
                    var json = objectMapper.readValue(body, Map.class);
                    Object detail = json.get("detail");
                    if (detail != null) {
                        return "Worker rejected request (HTTP " + status + "): " + detail;
                    }
                } catch (Exception ignored) {
                    // Not JSON — use raw body
                }
                return "Worker rejected request (HTTP " + status + "): " + body;
            }
            return "Worker rejected request (HTTP " + status + ")";
        }
        return "Worker connection error: " + e.getMessage();
    }

    private void publishMessage(String sessionId, MessageType type, Map<String, Object> payload) {
        AgentMessage message = AgentMessage.of(sessionId, AGENT_ID, type, payload);
        log.debug("Publishing message: sessionId={}, type={}, payload={}", sessionId, type, payload);
        eventPublisher.publishEvent(message);
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }

    private String truncateResult(String result) {
        if (result == null) return null;
        return result.length() > 500 ? result.substring(0, 500) + "..." : result;
    }
}
