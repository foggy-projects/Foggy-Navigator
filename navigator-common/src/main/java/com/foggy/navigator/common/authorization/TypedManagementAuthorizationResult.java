package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Result of a binding typed-management ingress authentication decision. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TypedManagementAuthorizationResult(
        boolean allowed,
        AuthorizationReasonCode reasonCode,
        TypedManagementCredentialSource credentialSource,
        ManagementAuthenticationContext authenticationContext,
        PolicyDecisionV1 decision
) {
}
