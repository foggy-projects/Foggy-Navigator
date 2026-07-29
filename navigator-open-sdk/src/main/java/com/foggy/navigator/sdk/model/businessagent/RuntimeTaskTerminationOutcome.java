package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Outcome of one termination request.
 *
 * <p>{@link #ACCEPTED} means only that Navigator accepted the termination
 * request. Callers must inspect {@code canonicalTerminal} or reconcile the
 * original request id before treating the task as terminal.</p>
 */
public enum RuntimeTaskTerminationOutcome {
    /** Navigator accepted the request; this is not task terminal proof. */
    ACCEPTED,
    /** Navigator definitively rejected the request. */
    REJECTED,
    /** The task was already canonically terminal when termination was requested. */
    ALREADY_TERMINAL,
    /** A non-mutating dry-run completed. */
    DRY_RUN,
    /** Navigator recorded the request but cannot yet establish its outcome. */
    PROCESSING,
    /** A null, ambiguous, or future wire value. */
    UNKNOWN;

    @JsonCreator
    public static RuntimeTaskTerminationOutcome fromWireValue(String value) {
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
