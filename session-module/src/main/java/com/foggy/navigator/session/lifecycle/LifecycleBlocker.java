package com.foggy.navigator.session.lifecycle;

public enum LifecycleBlocker {
    WORKER_OFFLINE,
    WORKER_RECOVERING,
    STORAGE_FROZEN,
    CONFIGURATION_UNAVAILABLE,
    EVIDENCE_CONFLICT,
    WORKER_STATE_LOSS,
    WRITER_EXCLUSIVITY_LOST
}
