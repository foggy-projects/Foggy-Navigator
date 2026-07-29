package com.foggy.navigator.claude.worker.model.enums;

/** Formal outcome of one termination request. ACCEPTED is not terminal proof. */
public enum RuntimeTaskTerminationOutcome {
    ACCEPTED,
    REJECTED,
    ALREADY_TERMINAL,
    DRY_RUN,
    PROCESSING,
    UNKNOWN
}
