package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Comparison between the caller's expected Worker and Navigator's durable task binding. */
public enum RuntimeWorkerIdentityMatch {
    MATCHED,
    MISMATCHED,
    UNKNOWN;

    @JsonCreator
    public static RuntimeWorkerIdentityMatch fromWireValue(String value) {
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
