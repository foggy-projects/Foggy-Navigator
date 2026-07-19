package com.foggy.navigator.common.authorization;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/** Generates high-entropy opaque values suitable for hash-only storage. */
@Component
public final class OpaqueSecretGenerator {

    private static final int SECRET_BYTES = 32;
    private static final int REFERENCE_BYTES = 18;

    private final SecureRandom secureRandom = new SecureRandom();

    public OpaqueSecretMaterial generateSecret() {
        return OpaqueSecretMaterial.of(randomUrlSafe(SECRET_BYTES));
    }

    public String generateReference() {
        return randomUrlSafe(REFERENCE_BYTES);
    }

    private String randomUrlSafe(int byteCount) {
        byte[] bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
