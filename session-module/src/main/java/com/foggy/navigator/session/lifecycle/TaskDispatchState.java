package com.foggy.navigator.session.lifecycle;

public enum TaskDispatchState {
    NONE,
    RESERVED,
    DISPATCHED,
    WORKER_ACCEPTED,
    REJECTED
}
