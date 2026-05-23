package com.foggy.navigator.business.agent.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.entity.SessionMessageEntity;

import java.util.Map;
import java.util.Set;

/**
 * Default visibility policy for upstream business-agent conversation replay.
 */
public final class BusinessAgentSessionMessageVisibility {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> RAW_TOOL_CONTENTS = Set.of(
            "invoke_business_skill",
            "invoke_business_function",
            "submit_skill_result");

    private static final Set<String> RAW_TOOL_METADATA_TYPES = Set.of(
            "TOOL_CALL_START",
            "TOOL_CALL_RESULT",
            "TOOL_CALL_ERROR");

    private BusinessAgentSessionMessageVisibility() {
    }

    public static boolean isVisibleByDefault(SessionMessageEntity entity) {
        return !isInternalRuntimeMessage(entity);
    }

    public static boolean isInternalRuntimeMessage(SessionMessageEntity entity) {
        if (entity == null) {
            return true;
        }
        if ("TOOL".equalsIgnoreCase(entity.getRole())) {
            return true;
        }

        String content = normalize(entity.getContent());
        if (RAW_TOOL_CONTENTS.contains(content)) {
            return true;
        }

        Map<String, Object> metadata = parseMetadata(entity.getMetadata());
        String metadataType = upper(metadata.get("type"));
        if (RAW_TOOL_METADATA_TYPES.contains(metadataType)) {
            return true;
        }

        return isRootFrameState(metadata, content);
    }

    private static boolean isRootFrameState(Map<String, Object> metadata, String content) {
        String metadataType = upper(metadata.get("type"));
        String subtype = normalize(metadata.get("subtype"));
        if (!"STATE_SYNC".equals(metadataType) || !"skill_frame_open".equals(subtype)) {
            return false;
        }

        String metadataContent = normalize(metadata.get("content"));
        return isRootFrameContent(content) || isRootFrameContent(metadataContent);
    }

    private static boolean isRootFrameContent(String content) {
        return "Opening conversation root frame".equals(content)
                || "Reusing conversation root frame".equals(content)
                || "Opening frame for skill: system.root".equals(content)
                || "Reusing frame for skill: system.root".equals(content);
    }

    private static Map<String, Object> parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(metadata, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String upper(Object value) {
        return normalize(value).toUpperCase();
    }
}
