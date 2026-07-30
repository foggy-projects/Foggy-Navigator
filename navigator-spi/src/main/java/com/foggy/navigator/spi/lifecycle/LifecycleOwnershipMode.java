package com.foggy.navigator.spi.lifecycle;

public enum LifecycleOwnershipMode {
    LEGACY,
    SHADOW,
    ENFORCED;

    public boolean canTransitionTo(LifecycleOwnershipMode next) {
        return next != null && next.ordinal() >= ordinal();
    }
}
