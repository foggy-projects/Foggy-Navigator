package com.foggy.navigator.common.authorization;

/**
 * Issues short-lived management tokens only after common canonical
 * authentication. Controllers must never write management-token entities or
 * generate bearer material directly.
 */
public interface TypedManagementTokenIssuer {

    /**
     * Issues CONTROL_ACCESS only after revalidating the guard-provided context
     * against current principal and credential state. The caller cannot supply
     * raw headers or entity data.
     */
    ManagementTokenIssuanceResult exchangeControl(
            ManagementAuthenticationContext authenticationContext
    );

    /**
     * Issues SECURITY_ACTION only after current-state revalidation and a trusted
     * {@link ManagementStepUpVerifier} verifies both proof dimensions.
     */
    ManagementTokenIssuanceResult authorizeSecurityAction(
            ManagementAuthenticationContext authenticationContext,
            ManagementSecurityActionAuthorizationRequest authorizationRequest
    );
}
