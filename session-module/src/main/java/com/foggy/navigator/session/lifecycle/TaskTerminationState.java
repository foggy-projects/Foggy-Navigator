package com.foggy.navigator.session.lifecycle;

public enum TaskTerminationState {
    NONE,
    REQUEST_ACCEPTED,
    DISPATCHED,
    ACKNOWLEDGED,
    REJECTED,
    AMBIGUOUS,
    CONFIRMED
}
