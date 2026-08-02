package com.foggy.navigator.spi.recovery;

import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical provider identity used only to select background recovery policy. */
public record BackgroundRecoveryProviderId(String value) {

    private static final Pattern CANONICAL_ID =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    public BackgroundRecoveryProviderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("background recovery providerId must not be blank");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!CANONICAL_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("background recovery providerId must be canonical");
        }
    }

    public static BackgroundRecoveryProviderId of(String value) {
        return new BackgroundRecoveryProviderId(value);
    }
}
