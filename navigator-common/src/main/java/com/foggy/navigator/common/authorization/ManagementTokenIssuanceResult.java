package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Binding result for control-exchange or security-action token issuance. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManagementTokenIssuanceResult(
        boolean issued,
        AuthorizationReasonCode reasonCode,
        ManagementAuthenticationContext authenticationContext,
        IssuedManagementToken issuedToken,
        PolicyDecisionV1 decision
) {
}
