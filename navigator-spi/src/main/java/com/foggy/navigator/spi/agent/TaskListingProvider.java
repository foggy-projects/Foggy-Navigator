package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.DispatchTaskDTO;

import java.util.List;

/**
 * Narrow port for task list/search operations.
 */
public interface TaskListingProvider extends TaskProviderPort {

    /** Paginated task list. Prefer returning {@link TaskPageResult}; legacy DTO/Map envelopes remain supported. */
    default Object listTasksPaged(String userId, int page, int size, String state) {
        throw new UnsupportedOperationException("listTasksPaged not supported by " + getProviderType());
    }

    /** Search sessions. Prefer returning {@link TaskSearchResult}; legacy DTO/Map envelopes remain supported. */
    default Object searchSessions(String userId, String keyword, String workerId,
                                  String directoryId, int page, int size) {
        throw new UnsupportedOperationException("searchSessions not supported by " + getProviderType());
    }

    /** List tasks under a directory. */
    default List<DispatchTaskDTO> listTasksByDirectory(String userId, String directoryId) {
        throw new UnsupportedOperationException("listTasksByDirectory not supported by " + getProviderType());
    }

    /** Paginated task list under a directory. Prefer returning {@link TaskPageResult}. */
    default Object listTasksByDirectoryPaged(String userId, String directoryId, int page, int size, String state) {
        throw new UnsupportedOperationException("listTasksByDirectoryPaged not supported by " + getProviderType());
    }
}
