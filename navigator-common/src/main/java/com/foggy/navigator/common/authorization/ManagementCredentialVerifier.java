package com.foggy.navigator.common.authorization;

/**
 * Pluggable verifier for a typed management credential reference. Implementors
 * must use a protected verifier store and must not log/persist request material.
 * No implementation is supplied by default, so a default deployment is deny-all.
 */
public interface ManagementCredentialVerifier {

    boolean supports(String verifierReference);

    boolean verify(ManagementCredentialVerificationRequest request);
}
