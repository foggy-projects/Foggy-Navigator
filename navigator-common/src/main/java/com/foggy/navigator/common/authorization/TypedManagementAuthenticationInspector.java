package com.foggy.navigator.common.authorization;

/**
 * Translates an already authenticated safe context into whoami/permission
 * information. It does not read entities or grant a second source of truth.
 */
public interface TypedManagementAuthenticationInspector {

    ManagementAuthenticationInspection inspect(ManagementAuthenticationContext authenticationContext);
}
