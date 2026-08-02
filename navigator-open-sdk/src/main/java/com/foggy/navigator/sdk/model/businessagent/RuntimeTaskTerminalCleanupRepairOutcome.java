package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Outcome of the dedicated, provider-neutral terminal cleanup repair. */
public enum RuntimeTaskTerminalCleanupRepairOutcome {
    READY,
    REPAIRED,
    ALREADY_CONVERGED,
    REJECTED,
    UNKNOWN;

    @JsonCreator
    public static RuntimeTaskTerminalCleanupRepairOutcome fromWireValue(String value) {
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
