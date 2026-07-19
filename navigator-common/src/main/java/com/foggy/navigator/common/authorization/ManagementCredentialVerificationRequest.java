package com.foggy.navigator.common.authorization;

/** Safe verifier input: a verifier receives the reference and one-way presentation digest only. */
public record ManagementCredentialVerificationRequest(
        String verifierReference,
        String presentationHash
) {
}
