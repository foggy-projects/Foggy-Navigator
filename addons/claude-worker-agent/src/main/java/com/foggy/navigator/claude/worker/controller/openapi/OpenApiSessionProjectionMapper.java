package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.core.type.TypeReference;
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
        if (!hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return null;
        }
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
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        List<OpenTaskReportRefDTO> refs = new ArrayList<>();
        collectReportRefs(refs, firstPresent(metadata,
                "reportRef", "report_ref", "frameReportRef", "frame_report_ref",
                "executionReportRef", "execution_report_ref", "reportRefs", "report_refs"));
        return refs.isEmpty() ? null : refs;
    }

    private void collectReportRefs(List<OpenTaskReportRefDTO> refs, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                collectReportRefs(refs, item);
            }
            return;
        }
        OpenTaskReportRefDTO dto = null;
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = toStringObjectMap(rawMap);
            String ref = stringValue(firstPresent(map,
                    "ref", "reportRef", "report_ref", "executionReportRef", "execution_report_ref"));
            dto = OpenTaskReportRefDTO.builder()
                    .type(firstNonBlank(stringValue(map.get("type")), inferReportRefType(ref)))
                    .ref(sanitizeDiagnosticText(ref))
                    .frameId(sanitizeDiagnosticText(firstNonBlank(
                            stringValue(map.get("frameId")), inferFrameId(ref))))
                    .summary(sanitizeDiagnosticText(stringValue(map.get("summary"))))
                    .build();
        } else if (raw instanceof String text && hasText(text)) {
            dto = OpenTaskReportRefDTO.builder()
                    .type(inferReportRefType(text))
                    .ref(sanitizeDiagnosticText(text))
                    .frameId(sanitizeDiagnosticText(inferFrameId(text)))
                    .build();
        }
        if (dto != null && hasText(dto.getRef())) {
            String ref = dto.getRef();
            if (refs.stream().noneMatch(existing -> ref.equals(existing.getRef()))) {
                refs.add(dto);
            }
        }
    }

    private List<OpenTaskArtifactRefDTO> extractArtifactRefs(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        List<OpenTaskArtifactRefDTO> refs = new ArrayList<>();
        collectArtifactRefs(refs, firstPresent(metadata,
                "artifactRefs", "artifact_refs", "artifacts"));
        return refs.isEmpty() ? null : refs;
    }

    private void collectArtifactRefs(List<OpenTaskArtifactRefDTO> refs, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                collectArtifactRefs(refs, item);
            }
            return;
        }
        OpenTaskArtifactRefDTO dto = null;
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = toStringObjectMap(rawMap);
            dto = OpenTaskArtifactRefDTO.builder()
                    .path(safeArtifactRef(firstNonBlank(
                            stringValue(map.get("path")), stringValue(map.get("file")))))
                    .ref(safeArtifactRef(firstNonBlank(
                            stringValue(map.get("ref")), stringValue(map.get("id")))))
                    .summary(sanitizeDiagnosticText(stringValue(map.get("summary"))))
                    .hash(sanitizeDiagnosticText(stringValue(map.get("hash"))))
                    .mtime(sanitizeDiagnosticText(firstNonBlank(
                            stringValue(map.get("mtime")), stringValue(map.get("modifiedAt")))))
                    .build();
        } else if (raw instanceof String text && hasText(text)) {
            dto = OpenTaskArtifactRefDTO.builder().path(safeArtifactRef(text)).build();
        }
        if (dto != null && (hasText(dto.getPath()) || hasText(dto.getRef()))) {
            String key = firstNonBlank(dto.getRef(), dto.getPath());
            if (refs.stream().noneMatch(existing ->
                    key.equals(firstNonBlank(existing.getRef(), existing.getPath())))) {
                refs.add(dto);
            }
        }
    }

    private String inferMessageType(String role, Map<String, Object> metadata) {
        if ("USER".equalsIgnoreCase(role)) {
            return "USER";
        }
        if ("SYSTEM".equalsIgnoreCase(role)) {
            return "STATE";
        }
        if (metadata != null) {
            Object rawType = metadata.get("type");
            if (rawType instanceof String type) {
                return switch (type) {
                    case "TEXT_COMPLETE" -> "TEXT";
                    case "TOOL_CALL_START" -> "TOOL_CALL";
                    case "TOOL_CALL_RESULT", "TOOL_CALL_ERROR" -> "TOOL_RESULT";
                    case "TASK_COMPLETED" -> "RESULT";
                    case "STATE_SYNC" -> "STATE";
                    case "ERROR" -> "ERROR";
                    default -> "TEXT";
                };
            }
        }
        return "TEXT";
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
        if (value instanceof String text) {
            return sanitizeDiagnosticText(text);
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            rawMap.forEach((key, childValue) -> {
                if (key instanceof String keyText) {
                    sanitized.put(keyText, sanitizeEvidenceValue(childValue));
                }
            });
            return sanitized;
        }
        if (value instanceof List<?> rawList) {
            return rawList.stream().map(this::sanitizeEvidenceValue).toList();
        }
        return value;
    }

    private String sanitizeDiagnosticText(String text) {
        if (!hasText(text)) {
            return null;
        }
        String sanitized = text.replace('\n', ' ').replace('\r', ' ').trim()
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(access[_-]?token\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(token\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(client[_-]?secret\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(secret\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(password\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-[REDACTED]");
        return truncate(sanitized, 500);
    }

    private String safeArtifactRef(String value) {
        String sanitized = sanitizeDiagnosticText(value);
        if (!hasText(sanitized)) {
            return null;
        }
        int queryIndex = sanitized.indexOf('?');
        if (queryIndex > 0) {
            sanitized = sanitized.substring(0, queryIndex);
        }
        return truncate(sanitized, 300);
    }

    private String inferReportRefType(String ref) {
        if (!hasText(ref)) {
            return null;
        }
        return ref.startsWith("frame-report://") ? "frame_report" : "report";
    }

    private String inferFrameId(String ref) {
        if (!hasText(ref) || !ref.startsWith("frame-report://")) {
            return null;
        }
        int slash = ref.lastIndexOf('/');
        return slash >= 0 && slash + 1 < ref.length() ? ref.substring(slash + 1) : null;
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> rawMap) {
        Map<String, Object> map = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key instanceof String text) {
                map.put(text, value);
            }
        });
        return map;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null || map.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return hasText(text) ? text : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
