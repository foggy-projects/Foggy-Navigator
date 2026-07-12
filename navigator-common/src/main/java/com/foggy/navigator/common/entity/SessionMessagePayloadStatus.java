package com.foggy.navigator.common.entity;

/**
 * Lifecycle state of an externally stored session-message payload.
 *
 * <p>{@code PENDING} is reserved for a future reliable asynchronous write
 * workflow. The Stage 1/2 synchronous writer only emits {@code READY} or
 * {@code UNAVAILABLE}; callers must not expose a storage key through message
 * DTOs or metadata.</p>
 */
public enum SessionMessagePayloadStatus {
    /** Complete bytes were durably persisted and passed their integrity contract. */
    READY,
    /**
     * Reserved for a future reliable asynchronous writer only. It may be set
     * solely after complete bytes have been durably staged or a retry source is
     * recorded, and must transition to READY, UNAVAILABLE, or EXPIRED by its
     * finite retry deadline. Stage 1/2 never emits PENDING.
     */
    PENDING,
    /** Complete bytes could not be made durable and no reliable retry source remains. */
    UNAVAILABLE,
    /** A formerly durable object is outside its retention window (Stage 4). */
    EXPIRED
}
