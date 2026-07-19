package com.foggy.navigator.common.authorization;

/**
 * Trusted verifier SPI for security-action step-up and independent approval.
 * No default implementation exists: absent/unsupported/failed verification is
 * a deny, never a controller-declared bypass.
 */
public interface ManagementStepUpVerifier {

    boolean supports(ManagementSecurityActionBinding actionBinding);

    ManagementStepUpVerificationResult verify(ManagementStepUpVerificationRequest request);
}
