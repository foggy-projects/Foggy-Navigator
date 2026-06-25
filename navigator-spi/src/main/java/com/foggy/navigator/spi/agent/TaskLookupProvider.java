package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.DispatchTaskDTO;

import java.util.List;
import java.util.Optional;

/**
 * Narrow port for task lookup operations.
 */
public interface TaskLookupProvider extends TaskProviderPort {

    /** Query by taskId across users; internal use only. */
    Optional<DispatchTaskDTO> getTaskById(String taskId);

    /** Query by taskId and userId with caller-side authorization. */
    Optional<DispatchTaskDTO> getTaskByIdAndUser(String taskId, String userId);

    /** List all tasks under a platform session. */
    List<DispatchTaskDTO> listTasksBySession(String sessionId);

    /** List active tasks for a user. */
    List<DispatchTaskDTO> listActiveDispatchTasks(String userId);
}
