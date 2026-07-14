package com.foggy.navigator.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Shared codec for provider-owned JSON state stored on sessions and tasks.
 */
public final class ProviderStateCodec {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String FIELD_SCHEMA_VERSION = "schemaVersion";
    public static final String FIELD_PROVIDER_TYPE = "providerType";

    public static final String FIELD_CLAUDE_SESSION_ID = "claudeSessionId";
    public static final String FIELD_CODEX_THREAD_ID = "codexThreadId";
    public static final String FIELD_CODEX_HOME_KEY = "codexHomeKey";
    public static final String FIELD_CODEX_PRIVATE_ACCOUNT_ID = "privateAccountId";
    public static final String FIELD_CODEX_RUNTIME_ID = "codexRuntimeId";
    public static final String FIELD_CODEX_RUNTIME_REVISION = "codexRuntimeRevision";
    public static final String FIELD_CODEX_RUNTIME_TYPE = "codexRuntimeType";
    public static final String FIELD_CODEX_RUNTIME_INSTANCE_ID = "codexRuntimeInstanceId";
    public static final String FIELD_CODEX_ROUTING_EPOCH = "codexRoutingEpoch";
    public static final String FIELD_CREATED_AT_EPOCH_MS = "createdAtEpochMs";
    public static final String FIELD_RUNTIME_ACCEPTANCE_STATE = "runtimeAcceptanceState";
    public static final String FIELD_GEMINI_SESSION_ID = "geminiSessionId";
    public static final String FIELD_CONTEXT_ID = "contextId";
    public static final String FIELD_AGENT_TEAMS_CONFIG_ID = "agentTeamsConfigId";
    public static final String FIELD_CHECKPOINTS = "checkpoints";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private ProviderStateCodec() {
    }

    public static Map<String, Object> parseObject(String json) {
        Map<String, Object> state = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return state;
        }
        try {
            state.putAll(OBJECT_MAPPER.readValue(json, MAP_TYPE));
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
        return state;
    }

    public static Optional<String> readString(String json, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Object value = parseObject(json).get(key);
        if (value == null) {
            return Optional.empty();
        }
        String text = value.toString();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    public static String readStringOrNull(String json, String key) {
        return readString(json, key).orElse(null);
    }

    public static String mergeSessionValue(String existingJson, String providerType, String key, Object value) {
        return mergeSessionValues(existingJson, providerType, singleValue(key, value));
    }

    public static String mergeTaskValue(String existingJson, String providerType, String key, Object value) {
        return mergeTaskValues(existingJson, providerType, singleValue(key, value));
    }

    public static String mergeSessionValues(String existingJson, String providerType, Map<String, ?> values) {
        return mergeValues(existingJson, providerType, values);
    }

    public static String mergeTaskValues(String existingJson, String providerType, Map<String, ?> values) {
        return mergeValues(existingJson, providerType, values);
    }

    public static String writeObject(Map<String, ?> state) {
        if (state == null || !hasPayloadFields(state)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize provider state", e);
        }
    }

    private static String mergeValues(String existingJson, String providerType, Map<String, ?> values) {
        Map<String, Object> state = parseObject(existingJson);
        if (values != null) {
            values.forEach((key, value) -> applyValue(state, key, value));
        }
        if (!hasPayloadFields(state)) {
            return null;
        }
        state.put(FIELD_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);
        if (providerType != null && !providerType.isBlank()) {
            state.put(FIELD_PROVIDER_TYPE, providerType.trim());
        }
        return writeObject(state);
    }

    private static Map<String, Object> singleValue(String key, Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, value);
        return values;
    }

    private static void applyValue(Map<String, Object> state, String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (value == null) {
            state.remove(key);
            return;
        }
        if (value instanceof String text) {
            if (text.isBlank()) {
                state.remove(key);
            } else {
                state.put(key, text);
            }
            return;
        }
        state.put(key, value);
    }

    private static boolean hasPayloadFields(Map<String, ?> state) {
        return state != null && state.keySet().stream()
                .anyMatch(key -> !FIELD_SCHEMA_VERSION.equals(key) && !FIELD_PROVIDER_TYPE.equals(key));
    }
}
