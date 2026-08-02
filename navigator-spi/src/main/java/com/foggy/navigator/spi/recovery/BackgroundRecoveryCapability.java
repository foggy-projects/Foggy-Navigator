package com.foggy.navigator.spi.recovery;

/**
 * Provider-neutral kinds of automatic recovery scheduling.
 *
 * <p>Explicit reconnect, resync, read-only reconciliation and session resume
 * are deliberately not represented here and are therefore outside this
 * background policy.</p>
 */
public enum BackgroundRecoveryCapability {
    STARTUP_SCAN,
    DELAYED_RETRY,
    PERIODIC_RECOVERY_SCAN
}
