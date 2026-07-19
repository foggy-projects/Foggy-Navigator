package com.foggy.navigator.common.authorization;

/**
 * Result returned by a trusted verifier. The verifier must echo the exact
 * action binding it verified; this prevents a step-up proof for one target
 * from being combined with an approval for another target.
 *
 * <p>{@code approvalReference} is verifier-owned metadata and never request
 * proof material.</p>
 */
public record ManagementStepUpVerificationResult(
        ManagementSecurityActionBinding actionBinding,
        boolean stepUpSatisfied,
        boolean approvalSatisfied,
        String approvalReference
) {

    public static ManagementStepUpVerificationResult denied(ManagementSecurityActionBinding actionBinding) {
        return new ManagementStepUpVerificationResult(actionBinding, false, false, null);
    }
}
