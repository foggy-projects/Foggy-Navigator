package com.foggy.navigator.spi.recovery;

import java.util.Objects;

/** Effective switch and finite bounds for one provider in one deployment profile. */
public record BackgroundRecoveryPolicy(boolean enabled, BackgroundRecoveryBounds bounds) {

    public BackgroundRecoveryPolicy {
        bounds = Objects.requireNonNull(bounds, "background recovery bounds must not be null");
    }
}
