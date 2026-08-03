package com.foggy.navigator.spi.agent;

/**
 * Optional capability descriptor for typed task-provider port operations.
 * <p>
 * Providers may declare capabilities incrementally. Callers must keep backward
 * compatibility with older providers that do not declare this metadata yet.
 */
public enum TaskQueryCapability {
    CREATE_TASK_DIRECT,
    RESPOND_TO_TASK,
    RECONNECT_TASK,
    RESYNC_TASK,
    REWIND_TASK,
    RESUME_TASK,
    CANCEL_TASK,
    FORCE_CANCEL_TASK,
    DELETE_TASK,
    SCAN_CHECKPOINTS,
    LIST_TASKS_PAGED,
    SEARCH_SESSIONS,
    LIST_TASKS_BY_DIRECTORY,
    LIST_TASKS_BY_DIRECTORY_PAGED,
    LIST_WORKER_SESSIONS,
    GET_WORKER_SESSION_MESSAGE_COUNT,
    GET_WORKER_SESSION_MESSAGES,
    SYNC_WORKER_SESSIONS
}
