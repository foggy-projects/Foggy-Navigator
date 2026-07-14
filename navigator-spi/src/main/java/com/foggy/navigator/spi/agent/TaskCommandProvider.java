package com.foggy.navigator.spi.agent;

import com.foggy.navigator.common.dto.DispatchTaskDTO;

import java.util.Map;

/**
 * Narrow port for provider task commands and lifecycle operations.
 */
public interface TaskCommandProvider extends TaskProviderPort {

    /**
     * Directly create a provider task.
     */
    default DispatchTaskDTO createTaskDirect(Map<String, Object> params, String userId, String tenantId) {
        throw new UnsupportedOperationException("createTaskDirect not supported by " + getProviderType());
    }

    /** Reply to a permission request or provider question. */
    default void respondToTask(String taskId, String userId, Map<String, Object> response) {
        throw new UnsupportedOperationException("respond not supported by " + getProviderType());
    }

    /** Reconnect a task stream. */
    default void reconnectTask(String taskId, String userId) {
        throw new UnsupportedOperationException("reconnect not supported by " + getProviderType());
    }

    /** Resynchronize provider task state. */
    default Object resyncTask(String taskId, String userId) {
        throw new UnsupportedOperationException("resync not supported by " + getProviderType());
    }

    /** Rewind a task to a checkpoint. */
    default Object rewindTask(String taskId, String userId, Map<String, Object> params) {
        throw new UnsupportedOperationException("rewind not supported by " + getProviderType());
    }

    /** Resume an existing task/session. */
    default DispatchTaskDTO resumeTask(String userId, String tenantId, Map<String, Object> params) {
        throw new UnsupportedOperationException("resume not supported by " + getProviderType());
    }

    /** Directly cancel a provider task from the provider command route. */
    default void cancelTaskDirect(String taskId, String userId) {
        throw new UnsupportedOperationException("cancel not supported by " + getProviderType());
    }

    /** Delete a provider task. */
    default void deleteTask(String userId, String taskId) {
        throw new UnsupportedOperationException("delete not supported by " + getProviderType());
    }

    /** Scan provider checkpoints. */
    default Object scanCheckpoints(String taskId, String userId) {
        throw new UnsupportedOperationException("scanCheckpoints not supported by " + getProviderType());
    }
}
