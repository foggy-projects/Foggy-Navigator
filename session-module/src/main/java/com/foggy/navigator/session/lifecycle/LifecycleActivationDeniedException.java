package com.foggy.navigator.session.lifecycle;

public class LifecycleActivationDeniedException extends IllegalStateException {
    public LifecycleActivationDeniedException(String safeReasonCode) {
        super(safeReasonCode);
    }
}
