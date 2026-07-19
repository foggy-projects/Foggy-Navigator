package com.foggy.navigator.common.authorization;

/** Redacted preflight input; a preflight decision never functions as a capability. */
public record ManagementAuthorizationExplainRequest(
        String routeId,
        String actionId,
        ManagementSecurityActionBinding actionBinding
) {
}
