package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

/**
 * A short-lived in-memory presentation. Its textual form is deliberately
 * redacted so request logging, exception rendering and test diagnostics cannot
 * disclose a credential or bearer token.
 */
public final class OpaqueSecretMaterial {

    private static final String REDACTED = "[redacted]";

    private final String value;

    private OpaqueSecretMaterial(String value) {
        this.value = value;
    }

    public static OpaqueSecretMaterial of(String value) {
        return value == null ? null : new OpaqueSecretMaterial(value);
    }

    public boolean isBlank() {
        return value == null || value.isBlank();
    }

    /** Safe for diagnostics only; it never includes the underlying presentation. */
    public String redacted() {
        return REDACTED;
    }

    @JsonIgnore
    String value() {
        return value;
    }

    @Override
    public String toString() {
        return REDACTED;
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
