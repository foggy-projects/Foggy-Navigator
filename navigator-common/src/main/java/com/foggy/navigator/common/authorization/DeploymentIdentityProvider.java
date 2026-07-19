package com.foggy.navigator.common.authorization;

/**
 * Exposes the immutable deployment identity to canonical authorization components.
 */
@FunctionalInterface
public interface DeploymentIdentityProvider {

    DeploymentIdentity deploymentIdentity();
}
