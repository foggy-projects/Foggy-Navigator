package com.foggy.navigator.session.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes backend-private payload fields before an AgentMessage can reach a
 * session row or a client-facing SSE stream. Both common JSON field spellings
 * are rejected recursively because providers may send arbitrary nested maps.
 */
public final class SessionMessagePublicPayloadSanitizer {

    private SessionMessagePublicPayloadSanitizer() {
    }

    public static Object redactInternalStorageKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                String field = String.valueOf(key);
                if (!"storageKey".equals(field) && !"storage_key".equals(field)) {
                    safe.put(field, redactInternalStorageKeys(nested));
                }
            });
            return safe;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(SessionMessagePublicPayloadSanitizer::redactInternalStorageKeys).toList();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> redactInternalStorageKeys(Map<?, ?> payload) {
        return (Map<String, Object>) redactInternalStorageKeys((Object) payload);
    }
}
