package com.foggy.navigator.session.service.payload;

/** Readiness result intentionally suitable for logs and a future health endpoint. */
public record PayloadStoreReadiness(boolean ready, String code, String message) {

    public static PayloadStoreReadiness available() {
        return new PayloadStoreReadiness(true, "READY", "Session message payload store is ready");
    }

    public static PayloadStoreReadiness unavailable(String message) {
        return new PayloadStoreReadiness(false, "SESSION_MESSAGE_PAYLOAD_STORE_UNAVAILABLE", message);
    }
}
