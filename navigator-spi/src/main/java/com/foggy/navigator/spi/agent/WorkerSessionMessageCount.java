package com.foggy.navigator.spi.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed envelope for worker-session message counters.
 */
public record WorkerSessionMessageCount(
        long userCount,
        long assistantCount,
        long total,
        Map<String, Object> attributes) {

    public WorkerSessionMessageCount {
        attributes = copyAttributes(attributes);
    }

    public static WorkerSessionMessageCount of(long userCount, long assistantCount, long total) {
        return new WorkerSessionMessageCount(userCount, assistantCount, total, Map.of());
    }

    public static WorkerSessionMessageCount from(Object value) {
        if (value instanceof WorkerSessionMessageCount count) {
            return count;
        }
        long userCount = firstLong(value, 0L, "user_count", "userCount");
        long assistantCount = firstLong(value, 0L, "assistant_count", "assistantCount");
        long total = firstLong(value, userCount + assistantCount, "total");
        return new WorkerSessionMessageCount(userCount, assistantCount, total, attributes(value));
    }

    public static WorkerSessionMessageCount empty() {
        return new WorkerSessionMessageCount(0L, 0L, 0L, Map.of());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(attributes);
        map.putIfAbsent("user_count", userCount);
        map.putIfAbsent("assistant_count", assistantCount);
        map.putIfAbsent("total", total);
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
