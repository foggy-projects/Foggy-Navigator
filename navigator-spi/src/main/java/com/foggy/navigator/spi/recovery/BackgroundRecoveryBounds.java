package com.foggy.navigator.spi.recovery;

import java.time.Duration;
import java.util.Objects;

/** Finite scheduling bounds required whenever automatic recovery is enabled. */
public record BackgroundRecoveryBounds(
        int maxAttempts,
        Duration recoveryWindow,
        Duration initialBackoff,
        Duration maxBackoff,
        int maxConcurrentRecoveries,
        Duration scanInterval) {

    public BackgroundRecoveryBounds {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        recoveryWindow = requirePositive(recoveryWindow, "recoveryWindow");
        initialBackoff = requirePositive(initialBackoff, "initialBackoff");
        maxBackoff = requirePositive(maxBackoff, "maxBackoff");
        if (initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("initialBackoff must not exceed maxBackoff");
        }
        if (initialBackoff.compareTo(recoveryWindow) > 0) {
            throw new IllegalArgumentException("initialBackoff must not exceed recoveryWindow");
        }
        if (maxBackoff.compareTo(recoveryWindow) > 0) {
            throw new IllegalArgumentException("maxBackoff must not exceed recoveryWindow");
        }
        if (maxConcurrentRecoveries <= 0) {
            throw new IllegalArgumentException("maxConcurrentRecoveries must be positive");
        }
        scanInterval = requirePositive(scanInterval, "scanInterval");
    }

    private static Duration requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
