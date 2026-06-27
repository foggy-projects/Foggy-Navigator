package com.foggy.navigator.spi.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed envelope for worker-session sync results.
 */
public record WorkerSessionSyncResult(
        long synced,
        long total,
        String source,
        Map<String, Object> attributes) {

    public WorkerSessionSyncResult {
        attributes = copyAttributes(attributes);
    }

    public static WorkerSessionSyncResult of(long synced, long total) {
        return new WorkerSessionSyncResult(synced, total, null, Map.of());
    }

    public static WorkerSessionSyncResult of(long synced, long total, String source) {
        return new WorkerSessionSyncResult(synced, total, source, Map.of());
    }

    public static WorkerSessionSyncResult from(Object value) {
        if (value instanceof WorkerSessionSyncResult result) {
            return result;
        }
        return new WorkerSessionSyncResult(
                firstLong(value, 0L, "synced"),
                firstLong(value, 0L, "total"),
                stringValue(TaskResultEnvelopeAdapters.readProperty(value, "source")),
                attributes(value));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(attributes);
        map.putIfAbsent("synced", synced);
        map.putIfAbsent("total", total);
        if (source != null) {
            map.putIfAbsent("source", source);
        }
        return map;
    }

    private static long firstLong(Object target, long defaultValue, String... properties) {
        for (String property : properties) {
            long value = TaskResultEnvelopeAdapters.longProperty(target, property, Long.MIN_VALUE);
            if (value != Long.MIN_VALUE) {
                return value;
            }
        }
        return defaultValue;
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
}
