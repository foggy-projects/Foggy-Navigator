package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.support.LanggraphSkillNameContract;
import com.foggy.navigator.session.event.SessionEventListener;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

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
    private final SessionEventListener sessionEventListener;
    private final ObjectMapper objectMapper;
    private final LlmModelManager llmModelManager;

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
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = event.getProviderConfigValue("attachments");
            String modelConfigId = event.getProviderConfigString("modelConfigId");
            Map<String, Object> llmConfig = resolveLlmConfig(modelConfigId, workerId);
            Map<String, Object> visionLlmConfig = resolveVisionLlmConfig(
                    runtimeContextText(runtimeContext, "vision_model_config_id", "visionModelConfigId"),
                    workerId
            );
            Integer maxTurns = positiveInteger(firstPresent(event.getProviderConfig(), "maxTurns", "max_turns"));
            String skillName = resolveSkillName(event.getProviderConfig(), "worker start event providerConfig");

            Disposable subscription = client.streamQuery(
                    event.getPrompt(),
                    skillName,
                    context,
                    runtimeContext,
                    event.getModel(),
                    modelConfigId,
                    llmConfig,
                    visionLlmConfig,
                    taskId,
                    sessionId,
                    event.getUserId(),
                    event.getTenantId(),
                    maxTurns,
                    attachments
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
                            "taskId", taskId,
                            "status", "FAILED"));
        }
    }

    private Map<String, Object> resolveLlmConfig(String modelConfigId, String workerId) {
        if (!StringUtils.hasText(modelConfigId)) {
            return null;
        }
        llmModelManager.validateModelAccessForWorker(modelConfigId, workerId);
        LlmModelConfigDTO model = llmModelManager.getModelConfig(modelConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Model config not found: " + modelConfigId));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("model_config_id", modelConfigId);
        putTextIfPresent(config, "provider", resolveProvider(model));
        putTextIfPresent(config, "worker_backend", model.getWorkerBackend());
        putTextIfPresent(config, "base_url", model.getBaseUrl());
        putTextIfPresent(config, "model", model.getModelName());
        putTextIfPresent(config, "runtime_budget_preset_key", model.getRuntimeBudgetPresetKey());
        putTextIfPresent(config, "runtime_budget_override_json", model.getRuntimeBudgetOverrideJson());
        String apiKey = llmModelManager.getDecryptedApiKey(modelConfigId);
        putTextIfPresent(config, "api_key", apiKey);
        if (model.getEnvVars() != null && !model.getEnvVars().isEmpty()) {
            config.put("env_vars", model.getEnvVars());
        }
        return config;
    }

    private Map<String, Object> resolveVisionLlmConfig(String modelConfigId, String workerId) {
        if (!StringUtils.hasText(modelConfigId)) {
            return null;
        }
        return resolveLlmConfig(modelConfigId, workerId);
    }

    private String runtimeContextText(Map<String, Object> runtimeContext, String... keys) {
        if (runtimeContext == null || runtimeContext.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = runtimeContext.get(key);
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        return null;
    }

    private String resolveSkillName(Map<String, Object> values, String source) {
        return LanggraphSkillNameContract.resolve(values, (key, ignored) ->
                log.warn("Deprecated LangGraph skill alias '{}' received from {}; use 'skill_name'", key, source));
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
        }
        return null;
    }

    private Integer positiveInteger(Object value) {
        if (value instanceof Number number) {
            int parsed = number.intValue();
            return parsed > 0 ? parsed : null;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                int parsed = Integer.parseInt(text.trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String resolveProvider(LlmModelConfigDTO model) {
        Map<String, String> envVars = model.getEnvVars();
        if (envVars != null) {
            String configured = firstText(
                    envVars.get("NAVI_LLM_PROVIDER"),
                    envVars.get("BIZ_WORKER_LLM_PROVIDER"),
                    envVars.get("provider")
            );
            if (configured != null) {
                return configured;
            }
        }
        String modelName = model.getModelName();
        if (modelName != null && modelName.toLowerCase().startsWith("claude")) {
            return "anthropic";
        }
        return "openai";
    }

    private void handleEvent(ServerSentEvent<String> sse, String taskId, String sessionId) {
        try {
            String data = sse.data();
            if (data == null || data.isBlank()) return;

            JsonNode node = objectMapper.readTree(data);
            String type = node.path("type").asText("");

            // Map Python Worker event → AgentMessage (using existing MessageType enum)
            switch (type) {
                case "system" -> log.debug("LangGraph system event for task {}: {}",
                        taskId, node.path("content").asText(""));

                case "assistant_text" -> publishMessage(sessionId, MessageType.TEXT_COMPLETE,
                        buildSkillScopedPayload(node, taskId, null));

                case "skill_frame_open" -> publishMessage(sessionId, MessageType.STATE_SYNC,
                        buildSkillScopedPayload(node, taskId, "skill_frame_open"));

                case "skill_frame_close" -> publishMessage(sessionId, MessageType.STATE_SYNC,
                        buildSkillScopedPayload(node, taskId, "skill_frame_close"));

                case "task_progress" -> publishMessage(sessionId, MessageType.STATE_SYNC,
                        buildTaskProgressPayload(node, taskId));

                case "tool_use" -> publishToolUse(sessionId, taskId, node);

                case "tool_result", "skill_result_submit", "skill_result_reject" ->
                        publishToolResult(sessionId, taskId, node, type);

                case "result" -> {
                    String content = node.path("content").asText("");
                    JsonNode structuredOutputNode = firstPresent(node, "structured_output", "structuredOutput");
                    String structuredOutput = structuredOutputNode != null && !structuredOutputNode.isNull()
                            ? structuredOutputNode.toString() : null;
                    Long durationMs = node.has("duration_ms") && !node.get("duration_ms").isNull()
                            ? node.get("duration_ms").asLong() : null;

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("content", content);
                    payload.put("taskId", taskId);
                    payload.put("status", "COMPLETED");
                    if (structuredOutputNode != null && !structuredOutputNode.isNull()) {
                        Object structuredOutputValue = toObject(structuredOutputNode);
                        payload.put("structured_output", structuredOutputValue);
                        payload.put("structuredOutput", structuredOutputValue);
                    }
                    copyExecutionReportFields(payload, node);
                    publishMessage(sessionId, MessageType.TASK_COMPLETED,
                            payload);

                    taskService.completeTask(taskId, content, structuredOutput, durationMs);
                }

                case "approval_required", "skill_approval_request" ->
                        handleApprovalRequired(node, type, taskId, sessionId);

                case "error" -> {
                    String error = node.path("error").asText(node.path("content").asText("Unknown error"));
                    String reason = node.path("reason").asText("");
                    if (StringUtils.hasText(reason)) {
                        taskService.recordTaskInterruptionProjection(taskId, reason, error);
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("content", error);
                    payload.put("taskId", taskId);
                    payload.put("status", "FAILED");
                    publishMessage(sessionId, MessageType.ERROR, payload);
                    taskService.failTask(taskId, error);
                }

                default -> log.debug("Unknown event type '{}' for task {}", type, taskId);
            }

        } catch (Exception e) {
            log.warn("Failed to process SSE event for task {}: {}", taskId, e.getMessage());
        }
    }

    private Map<String, Object> buildSkillScopedPayload(JsonNode node, String taskId, String subtype) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", node.path("content").asText(""));
        if (subtype != null && !subtype.isBlank()) {
            payload.put("subtype", subtype);
        }
        payload.put("taskId", taskId);
        putTextIfPresent(payload, "skillFrameId", node, "skill_frame_id");
        putTextIfPresent(payload, "parentFrameId", node, "parent_frame_id");
        putTextIfPresent(payload, "skillId", node, "skill_id");
        copyExecutionReportFields(payload, node);
        if ("skill_frame_open".equals(subtype)) {
            payload.put("status", "RUNNING");
        } else if ("skill_frame_close".equals(subtype)) {
            payload.put("status", statusFromExecutionReportDigest(node, "COMPLETED"));
        }
        return payload;
    }

    private Map<String, Object> buildTaskProgressPayload(JsonNode node, String taskId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", node.path("content").asText(""));
        payload.put("subtype", "task_progress");
        payload.put("taskId", taskId);
        putTextIfPresent(payload, "progressType", firstText(
                node.path("progress_type").asText(""),
                node.path("progressType").asText("")));
        putTextIfPresent(payload, "reason", firstText(
                node.path("reason").asText(""),
                node.path("error").asText("")));
        putTextIfPresent(payload, "skillFrameId", node, "skill_frame_id");
        putTextIfPresent(payload, "parentFrameId", node, "parent_frame_id");
        putTextIfPresent(payload, "skillId", node, "skill_id");
        putNumberIfPresent(payload, "attempt", node, "attempt");
        putNumberIfPresent(payload, "maxAttempts", node, "max_attempts");
        putNumberIfPresent(payload, "nextRetryAfterMs", node, "next_retry_after_ms");
        putNumberIfPresent(payload, "remainingMs", node, "remaining_ms");
        putTextIfPresent(payload, "presentationHint", firstText(
                node.path("presentation_hint").asText(""),
                node.path("presentationHint").asText("")));
        JsonNode nestedPayload = node.get("payload");
        if (nestedPayload != null && !nestedPayload.isNull()) {
            payload.put("payload", toObject(nestedPayload));
        }
        return payload;
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
        putTextIfPresent(payload, "skillFrameId", node, "skill_frame_id");
        putTextIfPresent(payload, "parentFrameId", node, "parent_frame_id");
        putTextIfPresent(payload, "skillId", node, "skill_id");
        copyExecutionReportFields(payload, node);
        payload.put("status", "RUNNING");
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
        putTextIfPresent(payload, "skillFrameId", node, "skill_frame_id");
        putTextIfPresent(payload, "parentFrameId", node, "parent_frame_id");
        putTextIfPresent(payload, "skillId", node, "skill_id");
        copyExecutionReportFields(payload, node);
        payload.put("status", success ? statusFromExecutionReportDigest(node, "COMPLETED") : "FAILED");
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

    private static void putTextIfPresent(Map<String, Object> payload, String key, String value) {
        if (StringUtils.hasText(value)) {
            payload.put(key, value);
        }
    }

    private void putNumberIfPresent(Map<String, Object> payload, String targetKey, JsonNode node, String sourceKey) {
        JsonNode value = node.get(sourceKey);
        if (value != null && value.isNumber()) {
            payload.put(targetKey, value.numberValue());
        }
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void handleStreamError(Throwable error, String taskId, String sessionId) {
        String message = streamErrorMessage(error);
        log.warn("SSE stream error for langgraph task {}: {}", taskId, message);
        activeStreams.remove(taskId);
        String reason = streamInterruptionReason(error);
        taskService.recordTaskInterruption(taskId, reason, message);
        taskService.failTask(taskId, "Stream error: " + message);
        publishMessage(sessionId, MessageType.ERROR,
                Map.of("content", "Stream connection lost: " + message,
                        "taskId", taskId,
                        "status", "FAILED"));
    }

    private String streamErrorMessage(Throwable error) {
        if (error == null) {
            return "Unknown stream error";
        }
        if (StringUtils.hasText(error.getMessage())) {
            return error.getMessage();
        }
        return error.getClass().getSimpleName();
    }

    private String streamInterruptionReason(Throwable error) {
        if (isTimeout(error)) {
            return "stream_read_timeout";
        }
        return "stream_error";
    }

    private boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            String className = current.getClass().getName().toLowerCase();
            if (className.contains("timeout")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
        putTextIfPresent(payload, "skillFrameId", node, "skill_frame_id");
        putTextIfPresent(payload, "parentFrameId", node, "parent_frame_id");
        putTextIfPresent(payload, "skillId", node, "skill_id");
        payload.put("scriptRunId", node.path("script_run_id").asText(""));
        payload.put("suspendId", node.path("suspend_id").asText(""));
        payload.put("reason", node.path("reason").asText(""));
        payload.put("timeoutAt", node.path("timeout_at").asText(""));
        copyExecutionReportFields(payload, node);
        payload.put("status", statusFromExecutionReportDigest(node, "AWAITING_APPROVAL"));

        publishMessage(sessionId, MessageType.STATE_SYNC, payload);
    }

    private void putTextIfPresent(Map<String, Object> payload, String targetKey, JsonNode node, String sourceKey) {
        JsonNode value = node.get(sourceKey);
        if (value == null || value.isNull()) {
            return;
        }
        String text = value.asText("");
        if (!text.isBlank()) {
            payload.put(targetKey, text);
        }
    }

    private void copyExecutionReportFields(Map<String, Object> payload, JsonNode node) {
        JsonNode reportRef = firstPresent(node, "execution_report_ref", "executionReportRef");
        if (reportRef != null && !reportRef.isNull()) {
            String text = reportRef.asText("");
            if (!text.isBlank()) {
                payload.put("execution_report_ref", text);
            }
        }
        JsonNode reportDigest = firstPresent(node, "execution_report_digest", "executionReportDigest");
        if (reportDigest != null && reportDigest.isObject()) {
            payload.put("execution_report_digest", toObject(reportDigest));
        }
    }

    private String statusFromExecutionReportDigest(JsonNode node, String fallback) {
        JsonNode reportDigest = firstPresent(node, "execution_report_digest", "executionReportDigest");
        if (reportDigest != null && reportDigest.isObject()) {
            JsonNode status = reportDigest.get("status");
            if (status != null && status.isTextual() && StringUtils.hasText(status.asText())) {
                return status.asText();
            }
        }
        return fallback;
    }

    private JsonNode firstPresent(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
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
        sessionEventListener.handleMessage(msg);
    }
}
