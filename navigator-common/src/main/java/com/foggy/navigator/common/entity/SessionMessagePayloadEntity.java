package com.foggy.navigator.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Descriptor for a large session-message payload stored outside MySQL.
 *
 * <p>The descriptor deliberately keeps {@link #storageKey} database-only.
 * Session-message list/snapshot DTOs use a separate public descriptor and
 * must never serialize this entity directly.</p>
 */
@Data
@Entity
@Table(name = "session_message_payloads", indexes = {
        @Index(name = "uk_smp_message_id", columnList = "messageId", unique = true),
        @Index(name = "idx_smp_session_id", columnList = "sessionId"),
        @Index(name = "idx_smp_status_expires_at", columnList = "status,expiresAt"),
        @Index(name = "idx_smp_expires_at", columnList = "expiresAt")
})
public class SessionMessagePayloadEntity {

    /** Stable public-safe payload identifier, derived from the message id by the routing service. */
    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false, unique = true)
    private String messageId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    /** Storage implementation identifier, for example {@code filesystem}. */
    @Column(length = 32, nullable = false)
    private String backend;

    /** Backend-private object location. Never expose from session APIs. */
    @JsonIgnore
    @Column(length = 512)
    private String storageKey;

    @Column(length = 128, nullable = false)
    private String contentType;

    @Column(length = 32, nullable = false)
    private String contentEncoding;

    @Column(nullable = false)
    private Long originalBytes;

    @Column
    private Long storedBytes;

    @Column(length = 64, nullable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private SessionMessagePayloadStatus status;

    /** Nullable until a retention policy is explicitly enabled. */
    private LocalDateTime expiresAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
