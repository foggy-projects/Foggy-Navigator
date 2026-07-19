package com.foggy.navigator.common.authorization;

/** Canonical principal categories. P1A only observes legacy categories; it does not issue typed principals. */
public enum AuthorizationPrincipalType {
    NAVIGATOR_USER,
    UPSTREAM_SYSTEM_ADMIN,
    CLIENT_APP,
    TASK_CAPABILITY,
    WORKER_PRINCIPAL,
    INSTANCE_ROOT,
    SAAS_PLATFORM,
    UNKNOWN
}
