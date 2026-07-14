package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.DispatchTaskDTO;

import java.util.List;

/**
 * Narrow port for task list/search operations.
 */
public interface TaskListingProvider extends TaskProviderPort {

    /** Typed paginated task list. */
    default TaskPageResult listTaskPage(String userId, int page, int size, String state) {
        throw new UnsupportedOperationException("listTaskPage not supported by " + getProviderType());
    }

    /** Typed session search result page. */
    default TaskSearchResult searchSessionPage(String userId, String keyword, String workerId,
                                               String directoryId, int page, int size) {
        throw new UnsupportedOperationException("searchSessionPage not supported by " + getProviderType());
    }

    /** List tasks under a directory. */
    default List<DispatchTaskDTO> listTasksByDirectory(String userId, String directoryId) {
        throw new UnsupportedOperationException("listTasksByDirectory not supported by " + getProviderType());
    }

    /** Typed paginated task list under a directory. */
    default TaskPageResult listDirectoryTaskPage(String userId, String directoryId, int page, int size, String state) {
        throw new UnsupportedOperationException("listDirectoryTaskPage not supported by " + getProviderType());
    }
}
