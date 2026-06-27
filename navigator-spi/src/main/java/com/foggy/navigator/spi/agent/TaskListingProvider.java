package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.DispatchTaskDTO;

import java.util.List;

/**
 * Narrow port for task list/search operations.
 */
public interface TaskListingProvider extends TaskProviderPort {

    /** Typed paginated task list. */
    @SuppressWarnings("deprecation")
    default TaskPageResult listTaskPage(String userId, int page, int size, String state) {
        return TaskPageResult.from(listTasksPaged(userId, page, size, state), page, size);
    }

    /**
     * Legacy paginated task list envelope. Prefer overriding {@link #listTaskPage}.
     *
     * @deprecated since 1.3.1, use {@link #listTaskPage(String, int, int, String)}.
     */
    @Deprecated(since = "1.3.1", forRemoval = false)
    default Object listTasksPaged(String userId, int page, int size, String state) {
        throw new UnsupportedOperationException("listTasksPaged not supported by " + getProviderType());
    }

    /** Typed session search result page. */
    @SuppressWarnings("deprecation")
    default TaskSearchResult searchSessionPage(String userId, String keyword, String workerId,
                                               String directoryId, int page, int size) {
        return TaskSearchResult.from(searchSessions(userId, keyword, workerId, directoryId, page, size), page, size);
    }

    /**
     * Legacy search session envelope. Prefer overriding {@link #searchSessionPage}.
     *
     * @deprecated since 1.3.1, use {@link #searchSessionPage(String, String, String, String, int, int)}.
     */
    @Deprecated(since = "1.3.1", forRemoval = false)
    default Object searchSessions(String userId, String keyword, String workerId,
                                  String directoryId, int page, int size) {
        throw new UnsupportedOperationException("searchSessions not supported by " + getProviderType());
    }

    /** List tasks under a directory. */
    default List<DispatchTaskDTO> listTasksByDirectory(String userId, String directoryId) {
        throw new UnsupportedOperationException("listTasksByDirectory not supported by " + getProviderType());
    }

    /** Typed paginated task list under a directory. */
    @SuppressWarnings("deprecation")
    default TaskPageResult listDirectoryTaskPage(String userId, String directoryId, int page, int size, String state) {
        return TaskPageResult.from(listTasksByDirectoryPaged(userId, directoryId, page, size, state), page, size);
    }

    /**
     * Legacy paginated task list under a directory. Prefer overriding {@link #listDirectoryTaskPage}.
     *
     * @deprecated since 1.3.1, use {@link #listDirectoryTaskPage(String, String, int, int, String)}.
     */
    @Deprecated(since = "1.3.1", forRemoval = false)
    default Object listTasksByDirectoryPaged(String userId, String directoryId, int page, int size, String state) {
        throw new UnsupportedOperationException("listTasksByDirectoryPaged not supported by " + getProviderType());
    }
}
