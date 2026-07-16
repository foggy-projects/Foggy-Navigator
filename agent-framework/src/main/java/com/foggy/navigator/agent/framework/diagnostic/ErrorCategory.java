package com.foggy.navigator.agent.framework.diagnostic;

/** Stable, provider-neutral error categories exposed to clients. */
public enum ErrorCategory {
    AUTHENTICATION,
    AUTHORIZATION,
    CONFIGURATION,
    NETWORK,
    RATE_LIMIT,
    RUNTIME,
    TIMEOUT,
    CANCELLED,
    UNKNOWN
}
