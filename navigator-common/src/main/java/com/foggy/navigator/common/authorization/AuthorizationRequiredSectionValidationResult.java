package com.foggy.navigator.common.authorization;

/** Stable fail-closed result for an action-required typed context section. */
public record AuthorizationRequiredSectionValidationResult(
        boolean valid,
        AuthorizationDecisionOutcome outcome,
        AuthorizationReasonCode reasonCode
) {

    public static AuthorizationRequiredSectionValidationResult accepted() {
        return new AuthorizationRequiredSectionValidationResult(true, null, null);
    }

    public static AuthorizationRequiredSectionValidationResult denied(AuthorizationReasonCode reasonCode) {
        return new AuthorizationRequiredSectionValidationResult(false, AuthorizationDecisionOutcome.DENY, reasonCode);
    }

    public static AuthorizationRequiredSectionValidationResult unknown(AuthorizationReasonCode reasonCode) {
        return new AuthorizationRequiredSectionValidationResult(false, AuthorizationDecisionOutcome.UNKNOWN, reasonCode);
    }
}
