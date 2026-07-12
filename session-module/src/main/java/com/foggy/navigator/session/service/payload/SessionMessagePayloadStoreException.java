package com.foggy.navigator.session.service.payload;

/**
 * Explicit storage failure consumed by the durable-message coordinator.  The
 * {@link #code()} value distinguishes a non-writable backend from integrity and
 * input failures without exposing filesystem paths to clients.
 */
public class SessionMessagePayloadStoreException extends RuntimeException {

    private final String code;

    public SessionMessagePayloadStoreException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SessionMessagePayloadStoreException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
