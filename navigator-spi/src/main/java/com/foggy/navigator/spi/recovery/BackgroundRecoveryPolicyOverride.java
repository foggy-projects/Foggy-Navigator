package com.foggy.navigator.spi.recovery;

/** Sparse policy override; null fields inherit from the less-specific layer. */
public record BackgroundRecoveryPolicyOverride(
        Boolean enabled,
        BackgroundRecoveryBoundsOverride bounds) {

    public BackgroundRecoveryPolicy applyTo(BackgroundRecoveryPolicy base) {
        if (base == null) {
            throw new IllegalArgumentException("base background recovery policy must not be null");
        }
        return new BackgroundRecoveryPolicy(
                enabled == null ? base.enabled() : enabled,
                bounds == null ? base.bounds() : bounds.applyTo(base.bounds()));
    }
}
