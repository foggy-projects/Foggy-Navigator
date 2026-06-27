package com.foggy.navigator.spi.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Typed envelope for provider session search results.
 */
public record TaskSearchResult(List<Object> results, long total, int page, int size) {

    public TaskSearchResult {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static TaskSearchResult of(Collection<?> results, long total, int page, int size) {
        return new TaskSearchResult(copy(results), total, page, size);
    }

    public static TaskSearchResult from(Object searchResult, int defaultPage, int defaultSize) {
        if (searchResult instanceof TaskSearchResult result) {
            return result;
        }
        List<Object> results = TaskResultEnvelopeAdapters.listProperty(searchResult, "results");
        return new TaskSearchResult(
                results,
                TaskResultEnvelopeAdapters.longProperty(searchResult, "total", results.size()),
                TaskResultEnvelopeAdapters.intProperty(searchResult, "page", defaultPage),
                TaskResultEnvelopeAdapters.intProperty(searchResult, "size", defaultSize));
    }

    public static TaskSearchResult empty(int page, int size) {
        return new TaskSearchResult(List.of(), 0L, page, size);
    }

    private static List<Object> copy(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(values);
    }
}
