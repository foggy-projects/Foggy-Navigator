package com.foggy.navigator.session.lifecycle;

public enum SentinelReconcileState {
    READY,
    WORKER_UNAVAILABLE,
    IDENTITY_CHANGED,
    STATE_GENERATION_RESET,
    COVERAGE_GAP,
    LEASE_NOT_ACQUIRED
}
