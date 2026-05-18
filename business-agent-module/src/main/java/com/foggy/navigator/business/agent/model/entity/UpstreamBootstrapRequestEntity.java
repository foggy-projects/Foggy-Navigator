package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "upstream_bootstrap_request", indexes = {
        @Index(name = "idx_ubr_status", columnList = "status"),
        @Index(name = "idx_ubr_requested_tenant", columnList = "requestedTenantId"),
        @Index(name = "idx_ubr_upstream_system", columnList = "upstreamSystemId")
})
public class UpstreamBootstrapRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String requestId;

    @Column(length = 128, nullable = false, unique = true)
    private String requestCodeHash;

    @Column(length = 16, nullable = false)
    private String requestCodeSuffix;

    @Column(length = 128, nullable = false)
    private String claimTokenHash;

    @Column(length = 128, nullable = false)
    private String upstreamSystemId;

    @Column(length = 64, nullable = false)
    private String requestedTenantId;

    @Column(nullable = false)
    private Boolean multiTenant;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(length = 128)
    private String applicantLabel;

    @Column(length = 128)
    private String sourceIpHash;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(nullable = false)
    private LocalDateTime requestExpiresAt;

    private LocalDateTime approvedAt;

    @Column(length = 64)
    private String approvedByUserId;

    @Column(length = 64)
    private String approvedByOperatorCredentialId;

    private LocalDateTime deniedAt;

    @Column(columnDefinition = "TEXT")
    private String deniedReason;

    private LocalDateTime claimExpiresAt;

    private LocalDateTime consumedAt;

    @Column(columnDefinition = "TEXT")
    private String authorizedTenantIdsJson;

    @Column(length = 128)
    private String authorizedClientAppNamespace;

    @Column(columnDefinition = "TEXT")
    private String scopesJson;

    private LocalDateTime adminCredentialExpiresAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
