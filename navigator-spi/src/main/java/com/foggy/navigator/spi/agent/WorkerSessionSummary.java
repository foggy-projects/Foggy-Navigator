package com.foggy.navigator.spi.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed worker-session summary with raw attributes preserved for REST compatibility.
 */
public record WorkerSessionSummary(
        String sessionId,
        String workerId,
        String project,
        String model,
        String status,
        String latestTaskId,
        String prompt,
        Object createdAt,
        Object updatedAt,
        Map<String, Object> attributes) {

    public WorkerSessionSummary {
        attributes = copyAttributes(attributes);
    }

    public static WorkerSessionSummary of(String sessionId, String workerId, String project, String model,
                                          String status, String latestTaskId, String prompt,
                                          Object createdAt, Object updatedAt) {
        return new WorkerSessionSummary(sessionId, workerId, project, model, status, latestTaskId, prompt,
                createdAt, updatedAt, Map.of());
    }

    public static WorkerSessionSummary from(Object value) {
        if (value instanceof WorkerSessionSummary summary) {
            return summary;
        }
        return new WorkerSessionSummary(
                stringValue(firstProperty(value, "session_id", "sessionId")),
                stringValue(firstProperty(value, "worker_id", "workerId")),
                stringValue(firstProperty(value, "project")),
                stringValue(firstProperty(value, "model")),
                stringValue(firstProperty(value, "status")),
                stringValue(firstProperty(value, "latest_task_id", "taskId", "latestTaskId")),
                stringValue(firstProperty(value, "prompt")),
                firstProperty(value, "created_at", "createdAt"),
                firstProperty(value, "updated_at", "updatedAt"),
                attributes(value));
    }

    public static List<WorkerSessionSummary> fromList(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<WorkerSessionSummary> result = new ArrayList<>();
        for (Object value : values) {
            result.add(from(value));
        }
        return result;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(attributes);
        putIfAbsent(map, "session_id", sessionId);
        putIfAbsent(map, "sessionId", sessionId);
        putIfAbsent(map, "worker_id", workerId);
        putIfAbsent(map, "workerId", workerId);
        putIfAbsent(map, "project", project);
        putIfAbsent(map, "model", model);
        putIfAbsent(map, "status", status);
        putIfAbsent(map, "latest_task_id", latestTaskId);
        putIfAbsent(map, "taskId", latestTaskId);
        putIfAbsent(map, "prompt", prompt);
        putIfAbsent(map, "created_at", createdAt);
        putIfAbsent(map, "updated_at", updatedAt);
        return map;
    }

    static List<Map<String, Object>> toMapList(Collection<WorkerSessionSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of();
        }
        return summaries.stream().map(WorkerSessionSummary::toMap).toList();
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
