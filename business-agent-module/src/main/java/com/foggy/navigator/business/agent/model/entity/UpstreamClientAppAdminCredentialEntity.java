package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "upstream_client_app_admin_credential", indexes = {
        @Index(name = "idx_ucaac_upstream_system", columnList = "upstreamSystemId"),
        @Index(name = "idx_ucaac_status", columnList = "status"),
        @Index(name = "idx_ucaac_source_request", columnList = "sourceRequestId")
})
public class UpstreamClientAppAdminCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String credentialId;

    @Column(length = 128, nullable = false, unique = true)
    private String credentialKeyHash;

    @Column(length = 16, nullable = false)
    private String credentialKeyPrefix;

    @Column(length = 16, nullable = false)
    private String credentialKeySuffix;

    @Column(length = 128, nullable = false)
    private String upstreamSystemId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String authorizedTenantIdsJson;

    @Column(length = 128, nullable = false)
    private String authorizedClientAppNamespace;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String scopesJson;

    @Column(length = 32, nullable = false)
    private String status;

    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    private LocalDateTime lastUsedAt;

    @Column(length = 64, nullable = false)
    private String sourceRequestId;

    @Column(length = 64)
    private String issuedByUserId;

    @Column(length = 64)
    private String issuedByOperatorCredentialId;

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
