package com.foggy.navigator.common.authorization;

/** A compact diff between the existing effective result and a shadow-only canonical decision. */
public record AuthorizationDecisionDiff(
        LegacyEnforcementOutcome legacyOutcome,
        AuthorizationDecisionOutcome canonicalOutcome,
        boolean differs
) {

    public static AuthorizationDecisionDiff compare(LegacyEnforcementOutcome legacyOutcome,
                                                    PolicyDecisionV1 canonicalDecision) {
        AuthorizationDecisionOutcome outcome = canonicalDecision == null
                ? AuthorizationDecisionOutcome.UNKNOWN : canonicalDecision.decision();
        boolean differs = legacyOutcome == null || legacyOutcome == LegacyEnforcementOutcome.UNKNOWN
                || !legacyOutcome.name().equals(outcome.name());
        return new AuthorizationDecisionDiff(
                legacyOutcome == null ? LegacyEnforcementOutcome.UNKNOWN : legacyOutcome,
                outcome,
                differs
        );
    }
}
