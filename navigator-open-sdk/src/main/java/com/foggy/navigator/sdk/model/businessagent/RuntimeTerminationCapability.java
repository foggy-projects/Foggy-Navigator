package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Stable SDK view of whether the selected runtime supports task termination. */
public enum RuntimeTerminationCapability {
    SUPPORTED,
    UNAVAILABLE,
    UNKNOWN;

    @JsonCreator
    public static RuntimeTerminationCapability fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    @JsonValue
    public String toWireValue() {
        return name();
    }
}
