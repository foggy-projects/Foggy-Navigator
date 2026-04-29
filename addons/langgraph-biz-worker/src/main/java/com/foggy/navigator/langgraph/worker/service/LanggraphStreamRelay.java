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
            String modelConfigId = event.getProviderConfigString("modelConfigId");

            Disposable subscription = client.streamQuery(
                    event.getPrompt(),
                    context,
                    event.getModel(),
                    modelConfigId,
                    taskId,
                    sessionId
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

                case "skill_approval_request" -> {
                    String approvalType = node.path("approval_type").asText("");
                    String approvalSummary = node.path("content").asText("");
                    String approvalPayload = node.has("payload") && !node.get("payload").isNull()
                            ? node.get("payload").toString() : null;

                    // Persist audit record (Doc 31 §16.4: Java side manages audit)
                    // Resolve userId from task entity for the approval record
                    taskService.getTaskById(taskId).ifPresent(task ->
                            taskService.createApprovalRecord(
                                    taskId, sessionId, task.getUserId(),
                                    approvalType, approvalSummary, approvalPayload));

                    publishMessage(sessionId, MessageType.STATE_SYNC,
                            Map.of("content", approvalSummary,
                                    "subtype", "skill_approval_request",
                                    "taskId", taskId,
                                    "approvalType", approvalType,
                                    "skillFrameId", node.path("skill_frame_id").asText(""),
                                    "skillId", node.path("skill_id").asText("")));
                }

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

    private void handleStreamError(Throwable error, String taskId, String sessionId) {
        log.warn("SSE stream error for langgraph task {}: {}", taskId, error.getMessage());
        activeStreams.remove(taskId);
        taskService.failTask(taskId, "Stream error: " + error.getMessage());
        publishMessage(sessionId, MessageType.ERROR,
                Map.of("content", "Stream connection lost: " + error.getMessage(), "taskId", taskId));
    }

    private void handleStreamComplete(String taskId, String sessionId) {
        log.info("SSE stream completed for langgraph task {}", taskId);
        activeStreams.remove(taskId);
    }

    private void publishMessage(String sessionId, MessageType type, Map<String, Object> payload) {
        AgentMessage msg = AgentMessage.of(sessionId, LanggraphTaskService.PROVIDER_TYPE, type, payload);
        eventPublisher.publishEvent(msg);
    }
}
