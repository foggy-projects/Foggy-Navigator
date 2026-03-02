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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private final ClaudeWorkerService workerService;
    private final ClaudeTaskService taskService;
    private final ClaudeTaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** 活跃的流订阅，用于 abort */
    private final ConcurrentHashMap<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    /** Java taskId → Worker 内部 taskId 映射 (worker 生成的 UUID) */
    private final ConcurrentHashMap<String, String> workerTaskIdMap = new ConcurrentHashMap<>();

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
                    event.getTaskId(), event.getSessionId());

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId,
                    detectedModel, detectedClaudeSessionId, false);

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

        log.info("Reconnecting stream: taskId={}, sessionId={}, workerId={}", taskId, sessionId, workerId);

        try {
            ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
            ClaudeWorkerClient client = workerService.createClient(worker);

            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedClaudeSessionId = new AtomicReference<>();

            // Subscribe via the reconnection endpoint.
            // replay_from=0 replays all events — the relay logic is idempotent for
            // state-changing operations (completeTask/failTask check current status).
            Flux<ServerSentEvent<String>> sseFlux = client.subscribeToTask(taskId, 0);

            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId,
                    detectedModel, detectedClaudeSessionId, true);

            activeStreams.put(taskId, subscription);

            // Push a STATE_SYNC so the frontend knows the task stream is reconnected
            publishMessage(sessionId, MessageType.STATE_SYNC,
                    Map.of("content", "Task stream reconnected", "subtype", "reconnected", "taskId", taskId));

        } catch (Exception e) {
            log.warn("Failed to reconnect task {}: {}", taskId, e.getMessage());
            // Don't fail the task — Reconciler will handle it if CLI is truly dead.
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
     * @param isReconnect true 时不在 doOnComplete 中重试重连（避免无限循环），
     *                    false 时在 doOnComplete 中尝试一次重连。
     */
    private Disposable subscribeSseFlux(Flux<ServerSentEvent<String>> sseFlux,
                                         String taskId, String sessionId, String workerId,
                                         AtomicReference<String> detectedModel,
                                         AtomicReference<String> detectedClaudeSessionId,
                                         boolean isReconnect) {
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
                        relayEvent(sessionId, taskId, workerEvent, detectedModel, detectedClaudeSessionId);
                    } catch (Exception e) {
                        log.warn("Failed to parse worker event for task {}: {}", taskId, data, e);
                    }
                })
                .doOnComplete(() -> {
                    log.info("Worker stream completed: taskId={}, isReconnect={}", taskId, isReconnect);
                    activeStreams.remove(taskId);

                    if (!isReconnect) {
                        // First disconnect — attempt reconnection before failing.
                        // The Worker keeps the CLI alive during its grace period,
                        // so the subscribe endpoint should work if CLI is still running.
                        boolean reconnected = attemptReconnect(taskId, sessionId, workerId);
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
                })
                .doOnError(e -> {
                    log.error("Worker stream error: taskId={}", taskId, e);
                    activeStreams.remove(taskId);

                    if (!isReconnect) {
                        boolean reconnected = attemptReconnect(taskId, sessionId, workerId);
                        if (reconnected) {
                            return;
                        }
                    }

                    taskService.failTask(taskId, detectedClaudeSessionId.get(), e.getMessage());
                    publishMessage(sessionId, MessageType.ERROR,
                            Map.of("content", "Worker connection error: " + e.getMessage(), "taskId", taskId));
                })
                .subscribe();
    }

    /**
     * 尝试通过 Worker subscribe 端点重连。
     * 仅当任务仍在 RUNNING 或 AWAITING_PERMISSION 时尝试。
     *
     * @return true 如果重连成功启动
     */
    private boolean attemptReconnect(String taskId, String sessionId, String workerId) {
        try {
            // Check if the task is still active in DB
            var taskOpt = taskRepository.findByTaskId(taskId);
            if (taskOpt.isEmpty()) return false;
            String status = taskOpt.get().getStatus();
            if (!"RUNNING".equals(status) && !"AWAITING_PERMISSION".equals(status)) {
                return false;
            }

            log.info("Attempting SSE reconnection for task {} (status={})", taskId, status);

            ClaudeWorkerEntity worker = workerService.getWorkerEntity(workerId);
            ClaudeWorkerClient client = workerService.createClient(worker);

            AtomicReference<String> detectedModel = new AtomicReference<>();
            AtomicReference<String> detectedClaudeSessionId = new AtomicReference<>();

            Flux<ServerSentEvent<String>> sseFlux = client.subscribeToTask(taskId, 0);

            // isReconnect=true to prevent infinite reconnection loop
            Disposable subscription = subscribeSseFlux(sseFlux, taskId, sessionId, workerId,
                    detectedModel, detectedClaudeSessionId, true);

            activeStreams.put(taskId, subscription);

            publishMessage(sessionId, MessageType.STATE_SYNC,
                    Map.of("content", "Task stream reconnected", "subtype", "reconnected", "taskId", taskId));

            log.info("SSE reconnection successful for task {}", taskId);
            return true;

        } catch (Exception e) {
            log.warn("SSE reconnection failed for task {}: {}", taskId, e.getMessage());
            return false;
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
                publishMessage(sessionId, MessageType.TEXT_CHUNK, payload);
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

                // 发布跨 Agent 任务完成事件
                eventPublisher.publishEvent(TaskCompletionEvent.builder()
                        .externalTaskId(taskId)
                        .parentSessionId(sessionId)
                        .targetAgentId(AGENT_ID)
                        .status("COMPLETED")
                        .resultSummary(truncateResult(event.getResult()))
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

                // 发布跨 Agent 任务失败事件
                eventPublisher.publishEvent(TaskCompletionEvent.builder()
                        .externalTaskId(taskId)
                        .parentSessionId(sessionId)
                        .targetAgentId(AGENT_ID)
                        .status("FAILED")
                        .resultSummary(event.getError())
                        .build());
            }
            default -> log.debug("Unknown worker event type: {}", event.getType());
        }
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
