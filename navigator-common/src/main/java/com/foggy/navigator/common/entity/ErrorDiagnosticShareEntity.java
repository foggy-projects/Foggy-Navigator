package com.foggy.navigator.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** Revocable bearer capability granting read-only access to one diagnostic. */
@Data
@Entity
@Table(name = "error_diagnostic_shares", indexes = {
        @Index(name = "idx_eds_token_hash", columnList = "tokenHash", unique = true),
        @Index(name = "idx_eds_diagnostic_id", columnList = "diagnosticId"),
        @Index(name = "idx_eds_expires_at", columnList = "expiresAt")
})
public class ErrorDiagnosticShareEntity {

    @Id
    @Column(length = 64)
    private String shareId;

    @Column(length = 64, nullable = false)
    private String diagnosticId;

    @Column(length = 64, nullable = false, unique = true)
    private String tokenHash;

    @Column(length = 64, nullable = false)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    private LocalDateTime lastAccessAt;

    @Column(nullable = false)
    private Long accessCount;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (accessCount == null) accessCount = 0L;
    }
}
