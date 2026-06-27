package com.foggy.navigator.spi.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed worker-session message with raw attributes preserved for REST compatibility.
 */
public record WorkerSessionMessage(
        String role,
        String content,
        Object timestamp,
        String taskId,
        Map<String, Object> attributes) {

    public WorkerSessionMessage {
        attributes = copyAttributes(attributes);
    }

    public static WorkerSessionMessage of(String role, String content, Object timestamp, String taskId) {
        return new WorkerSessionMessage(role, content, timestamp, taskId, Map.of());
    }

    public static WorkerSessionMessage from(Object value) {
        if (value instanceof WorkerSessionMessage message) {
            return message;
        }
        return new WorkerSessionMessage(
                stringValue(firstProperty(value, "role")),
                stringValue(firstProperty(value, "content")),
                firstProperty(value, "timestamp", "created_at", "createdAt"),
                stringValue(firstProperty(value, "taskId", "task_id")),
                attributes(value));
    }

    public static List<WorkerSessionMessage> fromList(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<WorkerSessionMessage> result = new ArrayList<>();
        for (Object value : values) {
            result.add(from(value));
        }
        return result;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(attributes);
        putIfAbsent(map, "role", role);
        putIfAbsent(map, "content", content);
        putIfAbsent(map, "timestamp", timestamp);
        putIfAbsent(map, "taskId", taskId);
        return map;
    }

    static List<Map<String, Object>> toMapList(Collection<WorkerSessionMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream().map(WorkerSessionMessage::toMap).toList();
    }

    private static Object firstProperty(Object target, String... properties) {
        for (String property : properties) {
            Object value = TaskResultEnvelopeAdapters.readProperty(target, property);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> attributes(Object value) {
        if (!(value instanceof Map<?, ?> source) || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                attributes.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return attributes;
    }

    private static Map<String, Object> copyAttributes(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static void putIfAbsent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.putIfAbsent(key, value);
        }
    }
}
