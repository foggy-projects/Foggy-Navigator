package com.foggy.navigator.session.service.payload;

/** Metadata returned after a complete payload was durably written and verified. */
public record StoredSessionMessagePayload(
    String backend,
    String storageKey,
    String contentEncoding,
    long originalBytes,
    long storedBytes,
    String sha256
) {
}
