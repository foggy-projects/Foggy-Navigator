package com.foggy.navigator.spi.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Typed envelope for provider task listing results.
 */
public record TaskPageResult(List<Object> content, long totalSessions, int page, int size) {

    public TaskPageResult {
        content = content == null ? List.of() : List.copyOf(content);
    }

    public static TaskPageResult of(Collection<?> content, long totalSessions, int page, int size) {
        return new TaskPageResult(copy(content), totalSessions, page, size);
    }

    public static TaskPageResult empty(int page, int size) {
        return new TaskPageResult(List.of(), 0L, page, size);
    }

    private static List<Object> copy(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(values);
    }
}
