package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.event.ClaudeTaskStartEvent;
import com.foggy.navigator.claude.worker.model.event.WorkerEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Worker SSE → AgentMessage 桥接
 *
 * 监听 ClaudeTaskStartEvent，通过 WebClient 消费 Worker SSE 流，
 * 将每个 Worker 事件转为 AgentMessage 并 publishEvent，
 * 后续由现有 SessionEventListener 处理持久化和 SSE 推送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerStreamRelay {

    private static final String AGENT_ID = "claude-worker";

    private final ClaudeWorkerService workerService;
    private final ClaudeTaskService taskService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** 活跃的流订阅，用于 abort */
    private final ConcurrentHashMap<String, Disposable> activeStreams = new ConcurrentHashMap<>();

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

            Disposable subscription = client.streamQuery(event.getPrompt(), event.getCwd(),
                            event.getClaudeSessionId(), event.getModel(), event.getMaxTurns(),
                            event.getAgentTeamsJson(), event.getImages(),
                            event.getApiKey(), event.getAuthToken(), event.getBaseUrl(),
                            event.getPermissionMode())
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
                            relayEvent(sessionId, taskId, workerEvent, detectedModel, detectedClaudeSessionId);
                        } catch (Exception e) {
                            log.warn("Failed to parse worker event for task {}: {}", taskId, data, e);
                        }
                    })
                    .doOnComplete(() -> {
                        log.info("Worker stream completed: taskId={}", taskId);
                        activeStreams.remove(taskId);
                    })
                    .doOnError(e -> {
                        log.error("Worker stream error: taskId={}", taskId, e);
                        activeStreams.remove(taskId);
                        taskService.failTask(taskId, detectedClaudeSessionId.get(), e.getMessage());
                        publishMessage(sessionId, MessageType.ERROR,
                                Map.of("content", "Worker connection error: " + e.getMessage(), "taskId", taskId));
                    })
                    .subscribe();

            activeStreams.put(taskId, subscription);

        } catch (Exception e) {
            log.error("Failed to start stream relay: taskId={}", taskId, e);
            taskService.failTask(taskId, null, e.getMessage());
            publishMessage(sessionId, MessageType.ERROR,
                    Map.of("content", "Failed to connect to worker: " + e.getMessage(), "taskId", taskId));
        }
    }

    /**
     * 中止任务的流
     */
    public void abortStream(String taskId) {
        Disposable subscription = activeStreams.remove(taskId);
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
                publishMessage(sessionId, MessageType.SESSION_START,
                        Map.of("content", "Task started", "taskId", taskId,
                                "claudeSessionId", nullSafe(event.getSessionId())));
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
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("content", "Tool: " + event.getTool());
                payload.put("toolName", event.getTool());
                payload.put("toolInput", event.getInput());
                payload.put("taskId", taskId);
                publishMessage(sessionId, MessageType.TOOL_CALL_START, payload);
            }
            case "tool_result" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("content", event.getOutput());
                payload.put("toolName", event.getTool());
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
