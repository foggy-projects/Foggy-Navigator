package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionMessageDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionSummaryDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskArtifactRefDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenTaskReportRefDTO;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure projection from durable Session entities to the public Open API Session DTOs.
 *
 * <p>Callers remain responsible for ownership, visibility, queries, pagination and durable Task
 * status mapping. In particular, a message is terminal only when its own metadata carries the
 * canonical terminal marker; the owning Task status is projection context, not message evidence.
 */
@Component
public class OpenApiSessionProjectionMapper {

    private final ObjectMapper objectMapper;

    public OpenApiSessionProjectionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OpenSessionMessageDTO mapMessage(
            SessionMessageEntity entity,
            String contextId,
            String mappedOpenTaskStatus) {
        Map<String, Object> metadata = parseMap(entity.getMetadata());
        String type = inferMessageType(entity.getRole(), metadata);
        String terminalStatus = inferTerminalStatus(metadata);
        List<Map<String, Object>> attachments = extractAttachments(metadata);
        String eventKind = inferEventKind(entity.getRole(), type, terminalStatus, metadata);
        String progressType = inferProgressType(metadata, eventKind);
        List<OpenTaskReportRefDTO> reportRefs = extractReportRefs(metadata);
        List<OpenTaskArtifactRefDTO> artifactRefs = extractArtifactRefs(metadata);
        Object structuredOutput = sanitizeEvidenceValue(firstPresent(metadata,
                "structuredOutput", "structured_output", "outputJson", "output_json"));

        if (metadata != null) {
            metadata = new LinkedHashMap<>(metadata);
            metadata.remove("taskId");
        }

        return OpenSessionMessageDTO.builder()
                .messageId(entity.getId())
                .contextId(contextId)
                .taskId(entity.getTaskId())
                .role(entity.getRole() != null ? entity.getRole().toLowerCase() : null)
                .type(type)
                .eventKind(eventKind)
                .progressType(progressType)
                .content(sanitizeDiagnosticText(entity.getContent()))
                .status(hasText(mappedOpenTaskStatus) ? mappedOpenTaskStatus : null)
                .terminal(terminalStatus != null)
                .terminalStatus(terminalStatus)
                .metadata(metadata)
                .structuredOutput(structuredOutput)
                .attachments(attachments)
                .reportRefs(reportRefs)
                .artifactRefs(artifactRefs)
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public OpenSessionMessageDTO mapSyntheticTaskError(
            SessionTaskEntity taskEntity,
            String contextId,
            String mappedStatus,
            String terminalStatus,
            String failureSummary,
            String failureStage) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", "ERROR");
        metadata.put("source", "task_state");
        metadata.put("synthetic", true);
        if (hasText(failureStage)) {
            metadata.put("failureStage", failureStage);
        }
        if (hasText(taskEntity.getWorkerId())) {
            metadata.put("workerId", taskEntity.getWorkerId());
        }
        if (hasText(taskEntity.getProviderType())) {
            metadata.put("providerType", taskEntity.getProviderType());
        }
        return OpenSessionMessageDTO.builder()
                .messageId("task-error:" + taskEntity.getTaskId())
                .contextId(contextId)
                .taskId(taskEntity.getTaskId())
                .role("assistant")
                .type("ERROR")
                .eventKind("error")
                .content(firstNonBlank(failureSummary,
                        "Task failed without persisted runtime messages."))
                .status(mappedStatus)
                .terminal(true)
                .terminalStatus(terminalStatus)
                .metadata(metadata)
                .createdAt(taskEntity.getUpdatedAt() != null
                        ? taskEntity.getUpdatedAt()
                        : taskEntity.getCreatedAt())
                .build();
    }

    public OpenSessionSummaryDTO mapSummary(
            AgentConversationContextEntity context,
            String agentId,
            Map<String, String> latestTaskBySessionId,
            Map<String, String> firstUserMessageBySessionId) {
        String sessionId = context.getNavigatorSessionId();
        String latestTaskId = sessionId != null && latestTaskBySessionId != null
                ? latestTaskBySessionId.get(sessionId)
                : null;

        return OpenSessionSummaryDTO.builder()
                .contextId(context.getContextId())
                .agentId(agentId)
                .title(resolveSessionTitle(context, firstUserMessageBySessionId))
                .status("ACTIVE")
                .latestTaskId(latestTaskId)
                .clientContext(parseMap(context.getClientContextJson()))
                .createdAt(context.getCreatedAt())
                .updatedAt(context.getLastAccessedAt())
                .build();
    }

    private Map<String, Object> parseMap(String json) {
        return OpenApiProjectionSupport.parseNullableMap(objectMapper, json);
    }

    private String resolveSessionTitle(
            AgentConversationContextEntity context,
            Map<String, String> firstUserMessageBySessionId) {
        if (hasText(context.getContextAlias())) {
            return context.getContextAlias().trim();
        }
        String sessionId = context.getNavigatorSessionId();
        if (sessionId == null || firstUserMessageBySessionId == null) {
            return null;
        }
        String firstUserMessage = firstUserMessageBySessionId.get(sessionId);
        if (!hasText(firstUserMessage)) {
            return null;
        }
        return truncate(firstUserMessage.trim(), 120);
    }

    private List<Map<String, Object>> extractAttachments(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object rawAttachments = metadata.get("attachments");
        if (!(rawAttachments instanceof List<?> rawList) || rawList.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> attachments = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> attachment = toStringObjectMap(rawMap);
            if (!attachment.isEmpty()) {
                attachments.add(attachment);
            }
        }
        return attachments.isEmpty() ? null : attachments;
    }

    private List<OpenTaskReportRefDTO> extractReportRefs(Map<String, Object> metadata) {
        return OpenApiProjectionSupport.extractReportRefs(metadata);
    }

    private List<OpenTaskArtifactRefDTO> extractArtifactRefs(Map<String, Object> metadata) {
        return OpenApiProjectionSupport.extractArtifactRefs(metadata);
    }

    private String inferMessageType(String role, Map<String, Object> metadata) {
        return OpenApiProjectionSupport.inferMessageType(role, metadata);
    }

    private String inferEventKind(
            String role,
            String messageType,
            String terminalStatus,
            Map<String, Object> metadata) {
        String explicitKind = normalizedEventToken(firstPresent(metadata,
                "eventKind", "event_kind", "kind"));
        if (hasText(explicitKind)) {
            return explicitKind;
        }
        if ("FAILED".equals(terminalStatus)) {
            return "error";
        }
        if ("COMPLETED".equals(terminalStatus)) {
            return "final_marker";
        }
        String metadataType = stringValue(firstPresent(metadata,
                "type", "messageType", "message_type"));
        if (hasText(metadataType)) {
            String normalizedType = normalizedEventToken(metadataType);
            return switch (normalizedType) {
                case "text_delta", "delta" -> "text_delta";
                case "text_complete", "text", "message", "assistant_message" -> "text_complete";
                case "tool_call_start", "tool_call", "tool_use" -> "tool_call_summary";
                case "tool_call_result", "tool_result" -> "tool_result_summary";
                case "tool_call_error", "error" -> "error";
                case "task_completed", "task_complete", "final", "final_answer" -> "final_marker";
                case "structured_output", "output_json" -> "structured_output";
                case "heartbeat" -> "heartbeat";
                case "retry", "retrying", "backoff" -> "retrying";
                case "progress" -> "progress";
                case "state_sync" -> eventKindFromStateSubtype(metadata);
                default -> eventKindFromMessageType(messageType);
            };
        }
        if ("USER".equalsIgnoreCase(role) || "USER".equals(messageType)) {
            return "user_message";
        }
        if ("TOOL".equalsIgnoreCase(role) || "TOOL_RESULT".equals(messageType)) {
            return "tool_result_summary";
        }
        if ("SYSTEM".equalsIgnoreCase(role) || "STATE".equals(messageType)) {
            return "progress";
        }
        return eventKindFromMessageType(messageType);
    }

    private String eventKindFromMessageType(String messageType) {
        return switch (messageType) {
            case "TOOL_CALL" -> "tool_call_summary";
            case "TOOL_RESULT" -> "tool_result_summary";
            case "RESULT" -> "final_marker";
            case "ERROR" -> "error";
            case "STATE" -> "progress";
            case "USER" -> "user_message";
            default -> "text_complete";
        };
    }

    private String eventKindFromStateSubtype(Map<String, Object> metadata) {
        String subtype = normalizedEventToken(firstPresent(metadata,
                "subtype", "state", "stateType", "state_type", "progressType", "progress_type"));
        if (!hasText(subtype)) {
            return "progress";
        }
        if (subtype.contains("heartbeat") || subtype.contains("keepalive")) {
            return "heartbeat";
        }
        if (subtype.contains("retry") || subtype.contains("backoff")) {
            return "retrying";
        }
        if (subtype.contains("structured_output")) {
            return "structured_output";
        }
        if (subtype.contains("error") || subtype.contains("failed")) {
            return "error";
        }
        return "progress";
    }

    private String inferProgressType(Map<String, Object> metadata, String eventKind) {
        String explicitProgressType = normalizedEventToken(firstPresent(metadata,
                "progressType", "progress_type"));
        if (hasText(explicitProgressType)) {
            return explicitProgressType;
        }
        if (!hasText(eventKind)) {
            return null;
        }
        if ("heartbeat".equals(eventKind)) {
            return "heartbeat";
        }
        if ("retrying".equals(eventKind)) {
            return "retry";
        }
        if (!"progress".equals(eventKind)) {
            return null;
        }
        String subtype = normalizedEventToken(firstPresent(metadata,
                "subtype", "state", "stateType", "state_type", "stage", "phase"));
        return hasText(subtype) ? subtype : "progress";
    }

    private String normalizedEventToken(Object value) {
        String text = sanitizeDiagnosticText(stringValue(value));
        if (!hasText(text)) {
            return null;
        }
        String normalized = text.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replace('-', '_')
                .replace(' ', '_')
                .toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return hasText(normalized) ? truncate(normalized, 80) : null;
    }

    private String inferTerminalStatus(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object rawType = metadata.get("type");
        if (!(rawType instanceof String type)) {
            return null;
        }
        return switch (type) {
            case "TASK_COMPLETED" -> "COMPLETED";
            case "ERROR" -> "FAILED";
            default -> null;
        };
    }

    private Object sanitizeEvidenceValue(Object value) {
        return OpenApiProjectionSupport.sanitizeEvidenceValue(value);
    }

    private String sanitizeDiagnosticText(String text) {
        return OpenApiProjectionSupport.sanitizeDiagnosticText(text);
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> rawMap) {
        return OpenApiProjectionSupport.toStringObjectMap(rawMap);
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        return OpenApiProjectionSupport.firstPresent(map, keys);
    }

    private String firstNonBlank(String... values) {
        return OpenApiProjectionSupport.firstNonBlank(values);
    }

    private String stringValue(Object value) {
        return OpenApiProjectionSupport.stringValue(value);
    }

    private boolean hasText(String value) {
        return OpenApiProjectionSupport.hasText(value);
    }

    private String truncate(String text, int maxLength) {
        return OpenApiProjectionSupport.truncate(text, maxLength);
    }
}
