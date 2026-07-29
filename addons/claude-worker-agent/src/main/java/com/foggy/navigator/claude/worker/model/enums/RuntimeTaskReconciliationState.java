package com.foggy.navigator.claude.worker.model.enums;

/** Read-only authoritative state of an original termination request id. */
public enum RuntimeTaskReconciliationState {
    NOT_FOUND,
    IN_PROGRESS,
    ACCEPTED,
    REJECTED,
    TERMINAL,
    AMBIGUOUS,
    UNKNOWN
}
