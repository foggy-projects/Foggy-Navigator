package com.foggy.navigator.common.authorization;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 utility for opaque, high-entropy credential and token material. */
@Component
public final class OpaqueSecretHasher {

    public String hash(OpaqueSecretMaterial material) {
        return material == null ? null : hashUtf8(material.value());
    }

    public String hashUtf8(String value) {
        if (value == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public boolean matches(String expectedHash, OpaqueSecretMaterial material) {
        String actualHash = hash(material);
        return expectedHash != null && actualHash != null
                && MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII),
                actualHash.getBytes(StandardCharsets.US_ASCII));
    }
}
