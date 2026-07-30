package com.foggy.navigator.session.lifecycle;

public enum LifecycleConflictState {
    NONE,
    EVIDENCE_CONFLICT,
    WORKER_STATE_LOSS,
    LEGACY_WRITER_EXCLUSIVITY_LOST
}
