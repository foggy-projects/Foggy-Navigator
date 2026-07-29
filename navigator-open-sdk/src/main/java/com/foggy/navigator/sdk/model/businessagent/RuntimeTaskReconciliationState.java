package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Authoritative read-only state of a termination request identified by its original request id. */
public enum RuntimeTaskReconciliationState {
    /** No retained request receipt exists for the supplied id. */
    NOT_FOUND,
    /** Navigator recorded the request, but its outcome is not yet authoritative. */
    IN_PROGRESS,
    /** The termination request was accepted; the task may still be running. */
    ACCEPTED,
    /** The termination request was definitively rejected. */
    REJECTED,
    /** Current Navigator task facts are canonically terminal. */
    TERMINAL,
    /** Retained request/task facts cannot be mapped without guessing. */
    AMBIGUOUS,
    /** A null or future wire value unknown to this SDK version. */
    UNKNOWN;

    @JsonCreator
    public static RuntimeTaskReconciliationState fromWireValue(String value) {
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
