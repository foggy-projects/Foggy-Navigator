package com.foggy.navigator.common.authorization;

/** Observed only after legacy handling completes; it is never fed back into enforcement. */
public enum LegacyEnforcementOutcome {
    ALLOW,
    DENY,
    UNKNOWN
}
