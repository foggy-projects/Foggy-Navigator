package com.foggy.navigator.codex.worker.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * User-managed connection details for one Codex App Server worker.
 *
 * <p>The endpoint is intentionally separate from a runtime revision. A sync
 * creates an immutable runtime snapshot only when the endpoint configuration
 * or the advertised execution capability changes.</p>
 */
@Data
@Entity
@Table(name = "codex_app_server_endpoints",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_case_endpoint_id", columnNames = "endpointId"),
        indexes = @Index(name = "idx_case_worker", columnList = "workerId"))
public class CodexAppServerEndpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 48, nullable = false, updatable = false)
    private String endpointId;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 512, nullable = false)
    private String endpointUrl;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String authTokenCiphertext;

    /** Increments only when the endpoint URL or credential changes. */
    @Column(nullable = false)
    private Long configurationVersion;

    @Column(length = 32, nullable = false)
    private String lastSyncStatus;

    @Column(columnDefinition = "TEXT")
    private String lastSyncMessage;

    private LocalDateTime lastSyncedAt;

    @Column(length = 64)
    private String lastRuntimeId;

    private Integer lastRuntimeRevision;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (configurationVersion == null) configurationVersion = 1L;
        if (lastSyncStatus == null) lastSyncStatus = "PENDING";
        if (authTokenCiphertext == null) authTokenCiphertext = "";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
