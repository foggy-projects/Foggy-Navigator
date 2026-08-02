package com.foggy.navigator.spi.recovery;

import java.time.Duration;

/** Sparse, field-level bounds override for a provider or deployment profile. */
public record BackgroundRecoveryBoundsOverride(
        Integer maxAttempts,
        Duration recoveryWindow,
        Duration initialBackoff,
        Duration maxBackoff,
        Integer maxConcurrentRecoveries,
        Duration scanInterval) {

    public BackgroundRecoveryBoundsOverride {
        requirePositive(maxAttempts, "maxAttempts");
        requirePositive(recoveryWindow, "recoveryWindow");
        requirePositive(initialBackoff, "initialBackoff");
        requirePositive(maxBackoff, "maxBackoff");
        requirePositive(maxConcurrentRecoveries, "maxConcurrentRecoveries");
        requirePositive(scanInterval, "scanInterval");
        if (initialBackoff != null && maxBackoff != null
                && initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("initialBackoff must not exceed maxBackoff");
        }
    }

    public BackgroundRecoveryBounds applyTo(BackgroundRecoveryBounds base) {
        if (base == null) {
            throw new IllegalArgumentException("base background recovery bounds must not be null");
        }
        return new BackgroundRecoveryBounds(
                maxAttempts == null ? base.maxAttempts() : maxAttempts,
                recoveryWindow == null ? base.recoveryWindow() : recoveryWindow,
                initialBackoff == null ? base.initialBackoff() : initialBackoff,
                maxBackoff == null ? base.maxBackoff() : maxBackoff,
                maxConcurrentRecoveries == null
                        ? base.maxConcurrentRecoveries()
                        : maxConcurrentRecoveries,
                scanInterval == null ? base.scanInterval() : scanInterval);
    }

    private static void requirePositive(Integer value, String field) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(field + " must be positive when configured");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(field + " must be positive when configured");
        }
    }
}
