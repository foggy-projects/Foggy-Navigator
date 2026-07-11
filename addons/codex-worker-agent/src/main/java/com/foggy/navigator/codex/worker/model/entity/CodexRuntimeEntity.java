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

@Data
@Entity
@Table(name = "codex_runtime_revisions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_crr_runtime_revision", columnNames = {"runtimeId", "revision"}),
        indexes = {
                @Index(name = "idx_crr_worker_type", columnList = "workerId,runtimeType"),
                @Index(name = "idx_crr_routing", columnList = "enabled,readinessStatus,routingPolicy")
        })
public class CodexRuntimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String runtimeId;

    @Column(nullable = false)
    private Integer revision;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 32, nullable = false)
    private String runtimeType;

    @Column(length = 512, nullable = false)
    private String endpointUrl;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String authTokenCiphertext;

    @Column(length = 128)
    private String instanceId;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(length = 32, nullable = false)
    private String routingPolicy;

    @Column(nullable = false)
    private Integer rolloutPercentage;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private Long routingEpoch;

    @Column(length = 32, nullable = false)
    private String readinessStatus;

    @Column(length = 32)
    private String contractVersion;

    @Column(length = 64)
    private String cliVersion;

    @Column(length = 128)
    private String schemaDigest;

    @Column(length = 64, nullable = false)
    private String expectedCliVersion;

    @Column(length = 128)
    private String expectedSchemaDigest;

    @Column(columnDefinition = "TEXT")
    private String capabilityManifestJson;

    @Column(columnDefinition = "TEXT")
    private String readinessMessage;

    private LocalDateTime lastCapabilityAt;

    private LocalDateTime archivedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (enabled == null) enabled = false;
        if (routingPolicy == null) routingPolicy = "DARK";
        if (rolloutPercentage == null) rolloutPercentage = 0;
        if (priority == null) priority = 0;
        if (routingEpoch == null) routingEpoch = 1L;
        if (readinessStatus == null) readinessStatus = "PENDING";
        if (expectedCliVersion == null) expectedCliVersion = "0.144.1";
        if (expectedSchemaDigest == null) {
            expectedSchemaDigest = "6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
