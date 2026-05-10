package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE relay: consumes Python Worker SSE stream and publishes AgentMessage events
 * for session persistence and frontend delivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LanggraphStreamRelay {

    private final LanggraphWorkerService workerService;
    private final LanggraphTaskService taskService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    /**
     * Listen for LangGraph task start events and initiate SSE consumption.
     */
    @Async("sessionEventExecutor")
    @EventListener(condition = "#event.providerType == 'langgraph-biz-worker'")
    public void onTaskStart(WorkerTaskStartEvent event) {
        String taskId = event.getTaskId();
        String sessionId = event.getSessionId();
        String workerId = event.getWorkerId();

        log.info("Starting langgraph stream relay: taskId={}, sessionId={}, workerId={}",
                taskId, sessionId, workerId);

        // Publish SESSION_START
        publishMessage(sessionId, MessageType.SESSION_START,
                Map.of("content", "Connecting to LangGraph worker...", "taskId", taskId));

        try {
            LanggraphWorkerEntity worker = workerService.getWorkerEntity(workerId);
            var client = workerService.createClient(worker);

            taskService.startTask(taskId);

            // Extract context from providerConfig
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>) event.getProviderConfig().get("context");
            @SuppressWarnings("unchecked")
            Map<String, Object> runtimeContext = (Map<String, Object>) event.getProviderConfig().get("runtimeContext");
            String modelConfigId = event.getProviderConfigString("modelConfigId");

            Disposable subscription = client.streamQuery(
                    event.getPrompt(),
                    context,
                    runtimeContext,
                    event.getModel(),
                    modelConfigId,
                    taskId,
                    sessionId,
                    event.getUserId(),
                    event.getTenantId()
            ).doOnNext(sse -> handleEvent(sse, taskId, sessionId))
              .doOnError(e -> handleStreamError(e, taskId, sessionId))
              .doOnComplete(() -> handleStreamComplete(taskId, sessionId))
              .onErrorResume(e -> {
                  log.warn("SSE stream error for task {}", taskId, e);
                  return reactor.core.publisher.Mono.empty();
              })
              .subscribe();

            activeStreams.put(taskId, subscription);

        } catch (Exception e) {
            log.error("Failed to start langgraph stream relay: taskId={}", taskId, e);
            taskService.failTask(taskId, e.getMessage());
            publishMessage(sessionId, MessageType.ERROR,
                    Map.of("content", "Failed to connect to LangGraph worker: " + e.getMessage(),
                            "taskId", taskId));
        }
    }

    private void handleEvent(ServerSentEvent<String> sse, String taskId, String sessionId) {
        try {
            String data = sse.data();
            if (data == null || data.isBlank()) return;

            JsonNode node = objectMapper.readTree(data);
            String type = node.path("type").asText("");

            // Map Python Worker event → AgentMessage (using existing MessageType enum)
            switch (type) {
                case "system" -> publishMessage(sessionId, MessageType.STATE_SYNC,
                        Map.of("content", node.path("content").asText(""),
                                "subtype", "system",
                                "taskId", taskId));

                case "assistant_text" -> publishMessage(sessionId, MessageType.TEXT_COMPLETE,
                        Map.of("content", node.path("content").asText(""),
                                "taskId", taskId,
                                "skillFrameId", node.path("skill_frame_id").asText(""),
                                "skillId", node.path("skill_id").asText("")));

                case "skill_frame_open" -> publishMessage(sessionId, MessageType.STATE_SYNC,
                        Map.of("content", node.path("content").asText(""),
                                "subtype", "skill_frame_open",
                                "taskId", taskId,
                                "skillFrameId", node.path("skill_frame_id").asText(""),
                                "skillId", node.path("skill_id").asText("")));

                case "skill_frame_close" -> publishMessage(sessionId, MessageType.STATE_SYNC,
                        Map.of("content", node.path("content").asText(""),
                                "subtype", "skill_frame_close",
                                "taskId", taskId,
                                "skillFrameId", node.path("skill_frame_id").asText(""),
                                "skillId", node.path("skill_id").asText("")));

                case "tool_use" -> publishToolUse(sessionId, taskId, node);

                case "tool_result", "skill_result_submit", "skill_result_reject" ->
                        publishToolResult(sessionId, taskId, node, type);

                case "result" -> {
                    String content = node.path("content").asText("");
                    String structuredOutput = node.has("structured_output") && !node.get("structured_output").isNull()
                            ? node.get("structured_output").toString() : null;
                    Long durationMs = node.has("duration_ms") && !node.get("duration_ms").isNull()
                            ? node.get("duration_ms").asLong() : null;

                    publishMessage(sessionId, MessageType.TASK_COMPLETED,
                            Map.of("content", content, "taskId", taskId));

                    taskService.completeTask(taskId, content, structuredOutput, durationMs);
                }

                case "approval_required", "skill_approval_request" ->
                        handleApprovalRequired(node, type, taskId, sessionId);

                case "error" -> {
                    String error = node.path("error").asText(node.path("content").asText("Unknown error"));
                    publishMessage(sessionId, MessageType.ERROR,
                            Map.of("content", error, "taskId", taskId));
                    taskService.failTask(taskId, error);
                }

                default -> log.debug("Unknown event type '{}' for task {}", type, taskId);
            }

        } catch (Exception e) {
            log.warn("Failed to process SSE event for task {}: {}", taskId, e.getMessage());
        }
    }

    private void publishToolUse(String sessionId, String taskId, JsonNode node) {
        String toolName = node.path("tool_name").asText(node.path("content").asText(""));
        String toolCallId = node.path("tool_call_id").asText("");
        String functionId = node.path("function_id").asText("");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", toolName);
        payload.put("toolName", toolName);
        if (!toolCallId.isBlank()) {
            payload.put("toolCallId", toolCallId);
        }
        if (!functionId.isBlank()) {
            payload.put("functionId", functionId);
        }
        if (node.has("args") && !node.get("args").isNull()) {
            payload.put("args", toObject(node.get("args")));
        }
        payload.put("taskId", taskId);
        payload.put("skillFrameId", node.path("skill_frame_id").asText(""));
        payload.put("skillId", node.path("skill_id").asText(""));
        publishMessage(sessionId, MessageType.TOOL_CALL_START, payload);
    }

    private void publishToolResult(String sessionId, String taskId, JsonNode node, String eventType) {
        String content = node.path("content").asText("");
        String toolName = node.path("tool_name").asText("");
        String toolCallId = node.path("tool_call_id").asText("");
        String functionId = node.path("function_id").asText("");
        boolean success = !"skill_result_reject".equals(eventType)
                && (node.path("error").isMissingNode() || node.path("error").asText("").isBlank());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("subtype", eventType);
        if (!toolName.isBlank()) {
            payload.put("toolName", toolName);
        }
        if (!toolCallId.isBlank()) {
            payload.put("toolCallId", toolCallId);
        }
        if (!functionId.isBlank()) {
            payload.put("functionId", functionId);
        }
        if (node.has("args") && !node.get("args").isNull()) {
            payload.put("args", toObject(node.get("args")));
        }
        payload.put("taskId", taskId);
        payload.put("skillFrameId", node.path("skill_frame_id").asText(""));
        payload.put("skillId", node.path("skill_id").asText(""));
        payload.put("data", parseJsonOrText(content));
        payload.put("success", success);
        String error = node.path("error").asText("");
        if (!error.isBlank()) {
            payload.put("error", error);
        }

        publishMessage(sessionId,
                success ? MessageType.TOOL_CALL_RESULT : MessageType.TOOL_CALL_ERROR,
                payload);
    }

    private Object toObject(JsonNode node) {
        return objectMapper.convertValue(node, Object.class);
    }

    private Object parseJsonOrText(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        try {
            return objectMapper.readValue(content, Object.class);
        } catch (Exception ignored) {
            return content;
        }
    }

    private void handleStreamError(Throwable error, String taskId, String sessionId) {
        log.warn("SSE stream error for langgraph task {}: {}", taskId, error.getMessage());
        activeStreams.remove(taskId);
        taskService.failTask(taskId, "Stream error: " + error.getMessage());
        publishMessage(sessionId, MessageType.ERROR,
                Map.of("content", "Stream connection lost: " + error.getMessage(), "taskId", taskId));
    }

    private void handleApprovalRequired(JsonNode node, String eventType, String taskId, String sessionId) {
        String approvalType = node.path("approval_type").asText("");
        if (approvalType.isBlank()) {
            approvalType = node.path("reason").asText("");
        }
        String approvalSummary = node.path("content").asText("");
        String approvalPayload = node.has("payload") && !node.get("payload").isNull()
                ? node.get("payload").toString() : null;

        // Persist audit record (Doc 31 §16.4: Java side manages audit)
        // Resolve userId from task entity for the approval record
        String finalApprovalType = approvalType;
        taskService.getTaskById(taskId).ifPresent(task ->
                taskService.createApprovalRecord(
                        taskId, sessionId, task.getUserId(),
                        finalApprovalType, approvalSummary, approvalPayload));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", approvalSummary);
        payload.put("subtype", eventType);
        payload.put("taskId", taskId);
        payload.put("approvalType", approvalType);
        payload.put("skillFrameId", node.path("skill_frame_id").asText(""));
        payload.put("skillId", node.path("skill_id").asText(""));
        payload.put("scriptRunId", node.path("script_run_id").asText(""));
        payload.put("suspendId", node.path("suspend_id").asText(""));
        payload.put("reason", node.path("reason").asText(""));
        payload.put("timeoutAt", node.path("timeout_at").asText(""));

        publishMessage(sessionId, MessageType.STATE_SYNC, payload);
    }

    private void handleStreamComplete(String taskId, String sessionId) {
        log.info("SSE stream completed for langgraph task {}", taskId);
        activeStreams.remove(taskId);
    }

    private void publishMessage(String sessionId, MessageType type, Map<String, Object> payload) {
        AgentMessage msg = AgentMessage.of(sessionId, LanggraphTaskService.PROVIDER_TYPE, type, payload);
        Object taskId = payload.get("taskId");
        if (taskId instanceof String taskIdValue && !taskIdValue.isBlank()) {
            msg.setTaskId(taskIdValue);
        }
        eventPublisher.publishEvent(msg);
    }
}
