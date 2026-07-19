package com.foggy.navigator.common.authorization;

/**
 * Redacted input passed to a trusted step-up / approval verifier. Proof
 * material is never copied to authorization persistence or response DTOs.
 */
public record ManagementStepUpVerificationRequest(
        ManagementAuthenticationContext authenticationContext,
        ManagementSecurityActionBinding actionBinding,
        OpaqueSecretMaterial stepUpProof,
        OpaqueSecretMaterial approvalProof,
        String correlationId
) {
}
