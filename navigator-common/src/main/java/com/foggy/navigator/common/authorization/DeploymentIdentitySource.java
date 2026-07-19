package com.foggy.navigator.common.authorization;

/**
 * Provenance of a deployment identity. A dev fallback is never production usable.
 */
public enum DeploymentIdentitySource {
    CONFIGURED,
    DEV_FALLBACK
}
