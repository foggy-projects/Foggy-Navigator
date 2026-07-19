package com.foggy.navigator.common.authorization;

/**
 * Closed vocabulary for the optional, typed sections an action can require.
 * The source-controlled route catalog is the sole authority for selecting a
 * set for any action; this enum intentionally contains no route heuristics.
 */
public enum AuthorizationRequiredSection {
    PRINCIPAL,
    CREDENTIAL,
    TRUST,
    AUTHORITY,
    DELEGATION,
    TARGET,
    PLATFORM_GRANT,
    TENANT_AUTHORITY,
    CAPABILITY,
    WORKER_ROUTE
}
