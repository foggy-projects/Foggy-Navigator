package com.foggy.navigator.session.service.payload;

/**
 * Backend-neutral durable store for complete session-message payloads.
 *
 * <p>The database descriptor owns the selected backend and storage key.  Callers
 * must use a stable message id so a replay resolves to the same stored object.</p>
 */
public interface SessionMessagePayloadStore {

    /** A stable identifier for this storage backend (for example {@code filesystem}). */
    String backend();

    /**
     * Writes the complete UTF-8 payload, or returns the already verified object for
     * an idempotent replay with the same message id and bytes. Implementations must
     * reject divergent bytes for an existing stable message id.
     */
    StoredSessionMessagePayload write(SessionMessagePayload payload);

    /**
     * Reads and verifies a previously stored payload. Implementations must return
     * the original, uncompressed bytes.
     */
    byte[] read(String storageKey, String contentEncoding, String expectedSha256);

    /** Current backend readiness without attempting to persist application data. */
    PayloadStoreReadiness readiness();
}
