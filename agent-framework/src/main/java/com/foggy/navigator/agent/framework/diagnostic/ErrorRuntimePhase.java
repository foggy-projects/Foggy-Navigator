package com.foggy.navigator.agent.framework.diagnostic;

/** Stable runtime phases; provider-specific subtypes must not be used here. */
public enum ErrorRuntimePhase {
    REQUEST_VALIDATION,
    TASK_ACCEPTANCE,
    SESSION_INITIALIZATION,
    TURN_EXECUTION,
    TOOL_EXECUTION,
    EVENT_STREAM,
    RESULT_PERSISTENCE,
    TASK_RECONCILIATION,
    UNKNOWN
}
