package com.foggy.navigator.sdk.cli;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;

final class SecretMasker {
    private SecretMasker() {
    }

    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "(empty)";
        }
        String trimmed = value.trim();
        String hash = sha256Prefix(trimmed);
        if (trimmed.length() <= 8) {
            return "*** sha256=" + hash;
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4)
                + " sha256=" + hash;
    }

    static String redactKnownSecrets(String text, Collection<String> secrets) {
        if (text == null || text.isEmpty() || secrets == null || secrets.isEmpty()) {
            return text;
        }
        String redacted = text;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                redacted = redacted.replace(secret, "[REDACTED]");
            }
        }
        return redacted;
    }

    private static String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6 && i < bytes.length; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
