package com.foggy.navigator.spi.recovery;

import java.util.Locale;
import java.util.regex.Pattern;

/** Server-owned deployment profile used as the highest policy override layer. */
public record BackgroundRecoveryProfile(String value) {

    private static final Pattern CANONICAL_PROFILE =
            Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");

    public BackgroundRecoveryProfile {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("background recovery profile must not be blank");
        }
        value = canonical(value);
        if (!CANONICAL_PROFILE.matcher(value).matches()) {
            throw new IllegalArgumentException("background recovery profile must be canonical");
        }
    }

    public static BackgroundRecoveryProfile of(String value) {
        return new BackgroundRecoveryProfile(value);
    }

    private static String canonical(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "dev", "development", "internal-dev", "local" -> "local-dev";
            case "prod" -> "production";
            case "stage" -> "staging";
            default -> normalized;
        };
    }
}
