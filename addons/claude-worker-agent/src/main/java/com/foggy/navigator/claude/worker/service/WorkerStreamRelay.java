package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.event.TaskStartedEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.AgentMessageBuilder;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.protocol.WorkerEvent;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
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

    /** 重连退避基础延迟（毫秒），实际延迟 = 2^attempt * BASE */
    private static final long RECONNECT_BASE_DELAY_MS = 2000;
    /** 重连退避上限（毫秒），避免无限增长 */
    private static final long MAX_RECONNECT_BACKOFF_MS = 300_000;  // 5 minutes

    private final ClaudeWorkerService workerService;
    private final ClaudeTaskService taskService;
    private final ClaudeTaskRepository taskRepository;
    private final WorkingDirectoryRepository workingDirectoryRepository;
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

    private static final java.util.Set<String> TERMINAL_STATES = java.util.Set.of("COMPLETED", "FAILED", "ABORTED");

    @Async("sessionEventExecutor")
    @EventListener(condition = "#event.providerType == 'claude-worker'")
    public void onTaskStart(WorkerTaskStartEvent event) {
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

            // 从 providerConfig 提取 Claude 特有参数
            String claudeSessionId = event.getProviderConfigString("claudeSessionId");
            String agentTeamsJson = event.getProviderConfigString("agentTeamsJson");
            String images = event.getProviderConfigString("images");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = event.getProviderConfigValue("attachments");
            String authToken = event.getProviderConfigString("authToken");
            String baseUrl = event.getProviderConfigString("baseUrl");
            String permissionMode = event.getProviderConfigString("permissionMode");
            String navigatorApiKey = event.getProviderConfigString("navigatorApiKey");
            String navigatorApiBase = event.getProviderConfigString("navigatorApiBase");
            Map<String, String> extraEnvVars = null;
            Object rawEnvVars = event.getProviderConfig().get("extraEnvVars");
            if (rawEnvVars instanceof Map<?,?> m && !m.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, String> typed = (Map<String, String>) rawEnvVars;
                extraEnvVars = typed;
            }

            // 将空字符串转为 null（providerConfig 的 Map.of 不允许 null value）
            claudeSessionId = blankToNull(claudeSessionId);
            agentTeamsJson = blankToNull(agentTeamsJson);
            images = blankToNull(images);
            authToken = blankToNull(authToken);
            baseUrl = blankToNull(baseUrl);
            permissionMode = blankToNull(permissionMode);
            navigatorApiKey = blankToNull(navigatorApiKey);
            navigatorApiBase = blankToNull(navigatorApiBase);

            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedClaudeSessionId = new AtomicReference<>(claudeSessionId);

            Flux<ServerSentEvent<String>> sseFlux = client.streamQuery(event.getPrompt(), event.getCwd(),
                    claudeSessionId, event.getModel(), event.getMaxTurns(),
                    agentTeamsJson, images, attachments,
                    event.getApiKey(), authToken, baseUrl,
                    permissionMode, navigatorApiKey, navigatorApiBase,
                    event.getTaskId(), event.getSessionId(),
                    extraEnvVars);

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId,
                    detectedModel, detectedClaudeSessionId, 0);

            activeStreams.put(taskId, subscription);

            // 发布跨 Agent 任务开始事件
            eventPublisher.publishEvent(TaskStartedEvent.builder()
                    .externalTaskId(taskId)
                    .parentSessionId(sessionId)
                    .targetAgentId(AGENT_ID)
                    .prompt(truncateResult(event.getPrompt()))
                    .extData(buildEventExtData(taskId))
                    .build());

        } catch (Exception e) {
            log.error("Failed to start stream relay: taskId={}", taskId, e);
            taskService.failTask(taskId, null, null, e.getMessage());
            publishMessage(sessionId, MessageType.ERROR,
                    Map.of("content", "Failed to connect to worker: " + e.getMessage(), "taskId", taskId));
        }
    }

    private String blankToNull(String s) {
        return s != null && !s.isBlank() ? s : null;
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
            ClaudeTaskEntity entity = taskRepository.findByTaskId(taskId).orElse(null);
            if (entity == null) {
                log.warn("reconnectTask: task {} not found in repository", taskId);
                return;
            }
            if (!isRecoverableTaskStatus(entity.getStatus())) {
                log.info("reconnectTask: task {} is not recoverable (status={}), skipping",
                        taskId, entity.getStatus());
                return;
            }

            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedClaudeSessionId = new AtomicReference<>(entity.getClaudeSessionId());

            // 用 lastAckedSeq（ESN）计算 ack_seq：
            // - 有 seq → 精确回放遗漏的事件（Worker 返回 seq > ack_seq 的所有事件）
            // - 无 seq（Java 重启） → ack_seq=0，从头回放（安全，幂等）
            AtomicInteger seqTracker = lastAckedSeq.get(taskId);
            int memoryAckSeq = seqTracker != null ? seqTracker.get() : 0;
            int persistedAckSeq = entity.getLastAckedSeq() != null ? entity.getLastAckedSeq() : 0;
            int ackSeq = Math.max(memoryAckSeq, persistedAckSeq);
            String subscribeTaskId = resolveWorkerTaskLookupId(entity);
            if (isClosedAndAligned(client, subscribeTaskId, ackSeq, taskId, "reconnectTask")) {
                lastAckedSeq.remove(taskId);
                return;
            }
            log.info("reconnectTask ack_seq={} for task {} (tracker={})", ackSeq, taskId, seqTracker);
            Flux<ServerSentEvent<String>> sseFlux = client.subscribeToTask(subscribeTaskId, ackSeq);

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId,
                    detectedModel, detectedClaudeSessionId, 0);

            activeStreams.put(taskId, subscription);

            // Reset interactionState to PROCESSING — the previous SSE disconnect
            // may have left it as AWAITING_REPLY via handleStreamDisconnect path.
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
            // A2A 异步任务使用直接 HTTP 查询，没有 Worker CLI 进程，跳过 SSE 重连
            if ("A2A".equals(task.getSource())) {
                log.debug("Startup reconnect: skipping A2A async task {}", task.getTaskId());
                continue;
            }
            try {
                // Java 重启后 lastAckedSeq 为空 → 先查 Worker 状态判断是否需要回放
                try {
                    ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
                    ClaudeWorkerClient client = workerService.createClient(worker);
                    Map<String, Object> status = client.getTaskStatus(resolveWorkerTaskLookupId(task))
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
     *                         无上限重试，退避指数增长至 MAX_RECONNECT_BACKOFF_MS 封顶。
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
                        // 更新 ACK 序列号：优先用 Worker 注入的 seq（ESN 模式），
                        // 若 Worker 未返回 seq（旧版），降级为单调递增计数
                        Integer ackSeq;
                        if (workerEvent.getSeq() != null) {
                            ackSeq = lastAckedSeq.computeIfAbsent(taskId, k -> new AtomicInteger(0))
                                    .updateAndGet(cur -> Math.max(cur, workerEvent.getSeq()));
                        } else {
                            // 旧 Worker 兼容：无 seq 字段，按计数递增
                            ackSeq = lastAckedSeq.computeIfAbsent(taskId, k -> new AtomicInteger(0)).incrementAndGet();
                        }
                        if (workerEvent.getTaskId() != null && !workerEvent.getTaskId().isBlank()) {
                            workerTaskIdMap.put(taskId, workerEvent.getTaskId());
                            log.debug("Task {} mapped to worker task: {}", taskId, workerEvent.getTaskId());
                        }
                        taskService.recordWorkerProgress(taskId, workerEvent.getTaskId(),
                                workerEvent.getSessionId(), workerEvent.getModel(), ackSeq);
                        try {
                            relayEvent(sessionId, taskId, workerEvent, detectedModel, detectedClaudeSessionId);
                        } catch (Exception relayEx) {
                            log.warn("Failed to relay event for task {}, type={}: {}",
                                    taskId, workerEvent.getType(), relayEx.getMessage(), relayEx);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse worker event for task {}: {}", taskId, data, e);
                    }
                })
                .doOnComplete(() -> {
                    log.info("Worker stream completed: taskId={}, reconnectAttempt={}", taskId, reconnectAttempt);
                    activeStreams.remove(taskId);

                    // Fast path: if lastAckedSeq was already cleaned up by relayEvent(result),
                    // the task completed successfully — skip reconnect without DB query.
                    // This avoids a race condition where the JPA transaction from completeTask()
                    // hasn't committed yet when we check the DB status below.
                    if (!lastAckedSeq.containsKey(taskId)) {
                        log.info("Task {} seq tracker already cleaned (completed via result event), skipping reconnect", taskId);
                        return;
                    }

                    // Check if task is already in a terminal state — no need to reconnect
                    var taskOpt = taskRepository.findByTaskId(taskId);
                    if (taskOpt.isPresent()) {
                        String status = taskOpt.get().getStatus();
                        if (TERMINAL_STATES.contains(status)) {
                            log.info("Task {} already in terminal state ({}), skipping reconnect", taskId, status);
                            lastAckedSeq.remove(taskId);
                            return;
                        }
                    }

                    // Infinite reconnect with capped exponential backoff (Rule 2: never auto-fail)
                    boolean reconnected = attemptReconnect(taskId, sessionId, workerId, reconnectAttempt);
                    if (reconnected) {
                        return;
                    }

                    // Reconnect failed — defer to Reconciler for lifecycle management.
                    // Do NOT call handleStreamDisconnect here; Reconciler will detect CLI state.
                    log.info("SSE reconnect attempt failed for task {} — Reconciler will manage lifecycle", taskId);
                    // Keep lastAckedSeq for future reconnect by Reconciler
                })
                .doOnError(e -> {
                    log.error("Worker stream error: taskId={}", taskId, e);
                    activeStreams.remove(taskId);

                    // Fast path: task already completed via result event — no need to reconnect or fail
                    if (!lastAckedSeq.containsKey(taskId)) {
                        log.info("Task {} seq tracker already cleaned (completed via result event), skipping error handling", taskId);
                        return;
                    }

                    // 4xx client errors (e.g. 429 Too Many Requests) are not transient —
                    // reconnecting won't help, fail immediately with a clear message.
                    boolean isClientError = e instanceof WebClientResponseException wce
                            && wce.getStatusCode().is4xxClientError();

                    if (isClientError) {
                        // Genuine startup failure — keep failTask for 4xx errors
                        String errorMsg = extractWorkerErrorMessage(e);
                        taskService.failTask(taskId, workerTaskIdMap.get(taskId), detectedClaudeSessionId.get(), errorMsg);
                        publishMessage(sessionId, MessageType.ERROR,
                                Map.of("content", errorMsg, "taskId", taskId));
                        lastAckedSeq.remove(taskId);
                        return;
                    }

                    // Transient error — infinite reconnect with capped backoff (Rule 2: never auto-fail)
                    boolean reconnected = attemptReconnect(taskId, sessionId, workerId, reconnectAttempt);
                    if (reconnected) {
                        return;
                    }

                    // Reconnect failed — defer to Reconciler for lifecycle management.
                    log.info("SSE reconnect attempt failed for task {} after error — Reconciler will manage lifecycle", taskId);
                    // Keep lastAckedSeq for future reconnect by Reconciler
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
            if (!isRecoverableTaskStatus(status)) {
                return false;
            }

            int nextAttempt = currentAttempt + 1;
            long delayMs = Math.min((long) Math.pow(2, currentAttempt) * RECONNECT_BASE_DELAY_MS, MAX_RECONNECT_BACKOFF_MS);
            log.info("Attempting SSE reconnection (attempt {}) for task {} (status={}), backoff={}ms",
                    nextAttempt, taskId, status, delayMs);

            Thread.sleep(delayMs);

            // Re-check task status after sleep — task may have completed during the backoff delay
            // (e.g. a "result" event arrived on a concurrent stream while we were sleeping)
            var freshTaskOpt = taskRepository.findByTaskId(taskId);
            if (freshTaskOpt.isPresent()) {
                String freshStatus = freshTaskOpt.get().getStatus();
                if (!isRecoverableTaskStatus(freshStatus)) {
                    log.info("Task {} status changed to {} during reconnect backoff, aborting reconnect", taskId, freshStatus);
                    return false;
                }
            }

            ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
            ClaudeWorkerClient client = workerService.createClient(worker);

            AtomicReference<String> detectedModel = new AtomicReference<>();
            ClaudeTaskEntity entity = freshTaskOpt.orElse(null);
            AtomicReference<String> detectedClaudeSessionId = new AtomicReference<>(
                    entity != null ? entity.getClaudeSessionId() : null);

            // 用 lastAckedSeq（ESN）计算 ack_seq
            AtomicInteger seqTracker = lastAckedSeq.get(taskId);
            int memoryAckSeq = seqTracker != null ? seqTracker.get() : 0;
            int persistedAckSeq = entity != null && entity.getLastAckedSeq() != null ? entity.getLastAckedSeq() : 0;
            int ackSeq = Math.max(memoryAckSeq, persistedAckSeq);
            String subscribeTaskId = resolveWorkerTaskLookupId(entity != null ? entity : taskOpt.get());
            if (isClosedAndAligned(client, subscribeTaskId, ackSeq, taskId, "attemptReconnect")) {
                lastAckedSeq.remove(taskId);
                return false;
            }
            log.info("attemptReconnect ack_seq={} for task {} (tracker={})", ackSeq, taskId, seqTracker);
            Flux<ServerSentEvent<String>> sseFlux = client.subscribeToTask(subscribeTaskId, ackSeq);

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId,
                    detectedModel, detectedClaudeSessionId, nextAttempt);

            activeStreams.put(taskId, subscription);

            // Reset interactionState to PROCESSING
            conversationConfigService.updateInteractionState(sessionId, "PROCESSING");

            publishMessage(sessionId, MessageType.STATE_SYNC,
                    Map.of("content", "Task stream reconnected", "subtype", "reconnected", "taskId", taskId));

            log.info("SSE reconnection successful for task {} (attempt {})", taskId, nextAttempt);
            return true;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("SSE reconnection interrupted for task {}", taskId);
            return false;
        } catch (Exception e) {
            log.warn("SSE reconnection failed for task {} (attempt {}): {}",
                    taskId, currentAttempt + 1, e.getMessage());
            return false;
        } finally {
            guard.set(false);
        }
    }

    private boolean isRecoverableTaskStatus(String status) {
        return "RUNNING".equals(status) || "AWAITING_PERMISSION".equals(status);
    }

    private boolean isClosedAndAligned(ClaudeWorkerClient client, String workerTaskId, int ackSeq,
                                       String taskId, String source) {
        try {
            Map<String, Object> status = client.getTaskStatus(workerTaskId)
                    .block(java.time.Duration.ofSeconds(5));
            if (status == null) return false;
            boolean closed = Boolean.TRUE.equals(status.get("closed"));
            int latestSeq = ((Number) status.getOrDefault("latest_seq", 0)).intValue();
            if (closed && latestSeq <= ackSeq) {
                log.info("{}: task {} Worker stream already closed and aligned (latestSeq={}, ackSeq={}), "
                        + "skipping SSE reconnect", source, taskId, latestSeq, ackSeq);
                return true;
            }
        } catch (Exception e) {
            log.debug("{}: cannot check Worker status before reconnect for task {}: {}",
                    source, taskId, e.getMessage());
        }
        return false;
    }

    /**
     * 获取 Worker 内部 task ID (用于 abort/respond 调用)
     * @param javaTaskId Java 侧的 task ID
     * @return Worker 生成的 UUID task ID, 如果未映射则返回 javaTaskId
     */
    public String getWorkerTaskId(String javaTaskId) {
        String runtimeWorkerTaskId = workerTaskIdMap.get(javaTaskId);
        if (runtimeWorkerTaskId != null && !runtimeWorkerTaskId.isBlank()) {
            return runtimeWorkerTaskId;
        }
        return taskRepository.findByTaskId(javaTaskId)
                .map(this::resolveWorkerTaskLookupId)
                .orElse(javaTaskId);
    }

    private String resolveWorkerTaskLookupId(ClaudeTaskEntity task) {
        return taskService.resolveWorkerTaskLookupId(task);
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

        // 使用 AgentMessageBuilder 标准化 payload 字段名
        AgentMessageBuilder mb = AgentMessageBuilder.create(sessionId, AGENT_ID).taskId(taskId);

        switch (event.getType()) {
            case "system" -> {
                // 系统消息，携带 session_id 等元数据 — 尽早记住 claudeSessionId
                if (event.getSessionId() != null) {
                    detectedClaudeSessionId.set(event.getSessionId());
                    try {
                        taskService.updateClaudeSessionId(taskId, event.getSessionId());
                        log.debug("Early saved claudeSessionId {} for task {}", event.getSessionId(), taskId);
                    } catch (Exception e) {
                        log.warn("Failed to early save claudeSessionId for task {}: {}", taskId, e.getMessage());
                    }
                }
                String subtype = event.getSubtype();
                if ("auto_compact".equals(subtype) || "context_compression".equals(subtype)) {
                    publishBuilt(mb.stateSync("Context compressed", subtype));
                } else if ("waiting".equals(subtype)) {
                    Map<String, Object> data = event.getData() != null ? event.getData() : Map.of();
                    publishBuilt(mb.stateSync("Waiting for response...", "waiting")
                            .put("elapsedSeconds", data.getOrDefault("elapsed_seconds", 0))
                            .put("timeoutSeconds", data.getOrDefault("timeout_seconds", 600)));
                } else {
                    String normalizedSubtype = (subtype == null || subtype.isBlank()) ? "system" : subtype;
                    String content = (event.getContent() == null || event.getContent().isBlank())
                            ? "Worker status updated"
                            : event.getContent();
                    publishBuilt(mb.stateSync(content, normalizedSubtype)
                            .put("claudeSessionId", nullSafe(event.getSessionId())));
                }
            }
            case "assistant_text" -> {
                if (event.getModel() != null) {
                    detectedModel.set(event.getModel());
                }
                publishBuilt(mb.textComplete(event.getContent()));
            }
            case "tool_use" -> {
                String toolCallId = event.getToolUseId() != null
                        ? event.getToolUseId() : "tc-" + System.nanoTime();
                publishBuilt(mb.toolCallStart(toolCallId, event.getTool(), event.getInput()));
            }
            case "tool_result" -> {
                publishBuilt(mb.toolCallResult(event.getToolUseId(), event.getTool(),
                        event.getOutput(), !Boolean.TRUE.equals(event.getIsError())));
            }
            case "result" -> {
                String resultContent = event.getContent() != null ? event.getContent() : event.getResult();
                String resolvedModel = event.getModel() != null ? event.getModel() : detectedModel.get();
                publishBuilt(mb.result(resultContent)
                        .metrics(event.getCostUsd(), event.getDurationMs(),
                                event.getInputTokens(), event.getOutputTokens(),
                                event.getNumTurns(), resolvedModel)
                        .put("claudeSessionId", nullSafe(event.getSessionId())));

                // 更新任务状态
                taskService.completeTask(taskId,
                        event.getTaskId() != null ? event.getTaskId() : getWorkerTaskId(taskId),
                        event.getSessionId(), resultContent, event.getCostUsd(),
                        event.getInputTokens(), event.getOutputTokens(), event.getDurationMs(),
                        event.getNumTurns(), resolvedModel);

                // 主动 dispose SSE 订阅
                Disposable completedSub = activeStreams.remove(taskId);
                if (completedSub != null && !completedSub.isDisposed()) {
                    completedSub.dispose();
                    log.info("Disposed SSE subscription after task completion: taskId={}", taskId);
                }
                reconnecting.remove(taskId);
                workerTaskIdMap.remove(taskId);
                lastAckedSeq.remove(taskId);

                autoScanCheckpoints(taskId, event.getSessionId());

                eventPublisher.publishEvent(TaskCompletionEvent.builder()
                        .externalTaskId(taskId)
                        .parentSessionId(sessionId)
                        .targetAgentId(AGENT_ID)
                        .status("COMPLETED")
                        .resultSummary(truncateResult(resultContent))
                        .extData(buildEventExtData(taskId))
                        .build());
            }
            case "permission_request" -> {
                publishBuilt(mb.confirmationRequest(event.getPermissionId())
                        .put("toolName", event.getTool())
                        .put("toolInput", event.getInput()));
                taskService.setAwaitingPermission(taskId);
            }
            case "user_question" -> {
                publishBuilt(mb.confirmationRequest(event.getPermissionId())
                        .put("questions", event.getQuestions()));
                taskService.setAwaitingPermission(taskId);
            }
            case "plan_review" -> {
                publishBuilt(mb.confirmationRequest(event.getPermissionId())
                        .put("planReview", true)
                        .put("allowedPrompts", event.getAllowedPrompts())
                        .put("plan", event.getPlan()));
                taskService.setAwaitingPermission(taskId);
            }
            case "checkpoint" -> {
                String checkpointId = event.getCheckpointId();
                if (checkpointId != null && !checkpointId.isEmpty()) {
                    taskService.addCheckpoint(taskId, checkpointId);
                    publishBuilt(mb.checkpoint(checkpointId));
                }
            }
            case "error" -> {
                if (event.getSessionId() != null) {
                    detectedClaudeSessionId.set(event.getSessionId());
                    try {
                        taskService.updateClaudeSessionId(taskId, event.getSessionId());
                        log.debug("Early saved claudeSessionId {} for task {} (error event)", event.getSessionId(), taskId);
                    } catch (Exception e) {
                        log.warn("Failed to early save claudeSessionId for task {} (error): {}", taskId, e.getMessage());
                    }
                }
                String errorClaudeSessionId = detectedClaudeSessionId.get();
                publishBuilt(mb.error(event.getError())
                        .put("claudeSessionId", nullSafe(errorClaudeSessionId)));

                taskService.failTask(taskId,
                        event.getTaskId() != null ? event.getTaskId() : getWorkerTaskId(taskId),
                        errorClaudeSessionId, event.getError());

                Disposable failedSub = activeStreams.remove(taskId);
                if (failedSub != null && !failedSub.isDisposed()) {
                    failedSub.dispose();
                    log.info("Disposed SSE subscription after task failure: taskId={}", taskId);
                }
                reconnecting.remove(taskId);
                workerTaskIdMap.remove(taskId);
                lastAckedSeq.remove(taskId);

                eventPublisher.publishEvent(TaskCompletionEvent.builder()
                        .externalTaskId(taskId)
                        .parentSessionId(sessionId)
                        .targetAgentId(AGENT_ID)
                        .status("FAILED")
                        .resultSummary(event.getError())
                        .extData(buildEventExtData(taskId))
                        .build());
            }
            case "sync_checkpoint" -> {
                Integer workerLatestSeq = event.getLatestSeq();
                Integer workerEventCount = event.getEventCount();
                AtomicInteger mySeq = lastAckedSeq.get(taskId);
                int myAckedSeq = mySeq != null ? mySeq.get() : 0;

                log.info("Sync checkpoint: taskId={}, workerLatestSeq={}, workerEventCount={}, myAckedSeq={}",
                        taskId, workerLatestSeq, workerEventCount, myAckedSeq);

                publishBuilt(mb.stateSync("Event stream verified", "sync_checkpoint")
                        .put("latestSeq", workerLatestSeq));
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

    /**
     * 任务完成后自动从 JSONL 扫描 checkpoints。
     * <p>
     * 流式 UserMessage.uuid 在 SDK 输出中不可靠（常为 null），
     * 而 JSONL 文件中一定有完整的 UUID。任务完成后立即扫描作为可靠补充。
     * 扫描失败不影响任务完成流程。
     */
    void autoScanCheckpoints(String taskId, String claudeSessionId) {
        if (claudeSessionId == null || claudeSessionId.isEmpty()) {
            log.debug("Skip auto-scan checkpoints: no claudeSessionId for task {}", taskId);
            return;
        }
        // 流式回调运行在 reactor-http-nio 线程，不能调用 block()，
        // 将扫描操作调度到 boundedElastic 线程池异步执行
        reactor.core.publisher.Mono.fromRunnable(() -> {
            try {
                ClaudeTaskEntity task = taskRepository.findByTaskId(taskId).orElse(null);
                if (task == null) return;

                // 始终用 JSONL 扫描结果覆盖，不跳过已有 checkpoints。
                // 原因：流式阶段可能添加了 tool_result 的 checkpoint（turnIndex 错位），
                // 而 JSONL 扫描只保留真实用户提问，turnIndex 与前端对齐。
                ClaudeWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
                ClaudeWorkerClient client = workerService.createClient(worker);
                List<Map<String, Object>> scanned = client.scanSessionCheckpoints(claudeSessionId)
                        .block(java.time.Duration.ofSeconds(15));
                if (scanned != null && !scanned.isEmpty()) {
                    taskService.scanAndPopulateCheckpoints(taskId, scanned);
                    log.info("Auto-scan checkpoints: taskId={}, count={}", taskId, scanned.size());
                } else {
                    log.debug("Auto-scan checkpoints: no checkpoints found for task {}", taskId);
                }
            } catch (Exception e) {
                // 扫描失败不影响任务完成流程
                log.warn("Auto-scan checkpoints failed for task {}: {}", taskId, e.getMessage());
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).subscribe();
    }

    private void publishMessage(String sessionId, MessageType type, Map<String, Object> payload) {
        AgentMessage message = AgentMessage.of(sessionId, AGENT_ID, type, payload);
        // 从 payload 中提取 taskId 并设置到消息对象级别，用于持久化
        if (payload != null && payload.containsKey("taskId")) {
            message.setTaskId((String) payload.get("taskId"));
        }
        log.debug("Publishing message: sessionId={}, type={}, payload={}", sessionId, type, payload);
        eventPublisher.publishEvent(message);
    }

    private void publishBuilt(AgentMessageBuilder builder) {
        eventPublisher.publishEvent(builder.build());
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }

    private String truncateResult(String result) {
        if (result == null) return null;
        return result.length() > 500 ? result.substring(0, 500) + "..." : result;
    }

    /**
     * 构建跨 Agent 事件的扩展数据（项目上下文）。
     * 从 ClaudeTaskEntity → WorkingDirectoryEntity 解析项目名称、Git 分支等信息。
     * 异常时返回空 map，不影响事件发布。
     */
    private Map<String, Object> buildEventExtData(String taskId) {
        Map<String, Object> ext = new LinkedHashMap<>();
        try {
            ClaudeTaskEntity task = taskRepository.findByTaskId(taskId).orElse(null);
            if (task == null) return ext;

            // 会话上下文
            if (task.getSessionId() != null) {
                ext.put("sessionId", task.getSessionId());
            }

            // 项目上下文 — 从 WorkingDirectory 获取
            if (task.getDirectoryId() != null) {
                workingDirectoryRepository.findByDirectoryId(task.getDirectoryId())
                        .ifPresent(dir -> {
                            ext.put("projectName", dir.getProjectName());
                            if (dir.getGitBranch() != null) {
                                ext.put("gitBranch", dir.getGitBranch());
                            }
                            if (dir.getGitRemoteUrl() != null) {
                                ext.put("gitRemoteUrl", dir.getGitRemoteUrl());
                            }
                        });
            } else if (task.getCwd() != null) {
                // Fallback: 从 cwd 路径末段推导项目名称
                String cwd = task.getCwd();
                int lastSlash = Math.max(cwd.lastIndexOf('/'), cwd.lastIndexOf('\\'));
                if (lastSlash >= 0 && lastSlash < cwd.length() - 1) {
                    ext.put("projectName", cwd.substring(lastSlash + 1));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to build extData for taskId={}: {}", taskId, e.getMessage());
        }
        return ext;
    }
}
