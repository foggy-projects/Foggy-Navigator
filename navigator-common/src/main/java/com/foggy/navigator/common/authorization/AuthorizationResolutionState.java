package com.foggy.navigator.common.authorization;

/** Distinguishes verified authority facts from intentionally non-enforcing shadow observations. */
public enum AuthorizationResolutionState {
    VERIFIED,
    UNVERIFIED,
    MISSING,
    CONFLICT,
    UNKNOWN
}
