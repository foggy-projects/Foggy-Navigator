package com.foggy.navigator.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** Provider-neutral, redacted diagnostic snapshot owned by one task/session. */
@Data
@Entity
@Table(name = "error_diagnostics", indexes = {
        @Index(name = "idx_ed_task_id", columnList = "taskId"),
        @Index(name = "idx_ed_session_id", columnList = "sessionId"),
        @Index(name = "idx_ed_owner_scope", columnList = "ownerUserId,tenantId"),
        @Index(name = "idx_ed_expires_at", columnList = "expiresAt")
})
public class ErrorDiagnosticEntity {

    @Id
    @Column(length = 64)
    private String diagnosticId;

    @Column(nullable = false)
    private Integer schemaVersion;

    @Column(nullable = false)
    private Integer redactionVersion;

    @Column(length = 64, nullable = false)
    private String taskId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 64, nullable = false)
    private String ownerUserId;

    @Column(length = 64)
    private String tenantId;

    @Column(length = 32, nullable = false)
    private String providerType;

    @Column(length = 32)
    private String runtimeType;

    @Column(length = 128)
    private String workerLabel;

    @Column(length = 160, nullable = false)
    private String errorCode;

    @Column(length = 32, nullable = false)
    private String category;

    @Column(length = 48, nullable = false)
    private String runtimePhase;

    @Column(length = 512, nullable = false)
    private String safeMessage;

    @Column(nullable = false)
    private Boolean recoverable;

    @Column(length = 160)
    private String providerStatus;

    private Integer httpStatus;

    private Integer retryCount;

    @Column(length = 160)
    private String exceptionType;

    @Column(columnDefinition = "TEXT")
    private String diagnosticText;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (occurredAt == null) occurredAt = now;
        if (schemaVersion == null) schemaVersion = 1;
        if (redactionVersion == null) redactionVersion = 1;
        if (recoverable == null) recoverable = false;
    }
}
