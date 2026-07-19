package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Explicitly non-binding result of a management authorization preflight. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManagementAuthorizationExplanation(
        boolean allowed,
        AuthorizationReasonCode reasonCode,
        boolean nonBinding,
        PolicyDecisionV1 decision
) {
}
