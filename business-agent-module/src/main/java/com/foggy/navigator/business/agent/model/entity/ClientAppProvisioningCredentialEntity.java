package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "client_app_provisioning_credential", indexes = {
        @Index(name = "idx_capc_tenant", columnList = "tenantId"),
        @Index(name = "idx_capc_status", columnList = "status")
})
public class ClientAppProvisioningCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String credentialId;

    @Column(length = 128, nullable = false, unique = true)
    private String tokenHash;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64)
    private String issuedByUserId;

    @Column(length = 64)
    private String ownerUserId;

    @Column(length = 64)
    private String capabilityDomain;

    @Column(length = 128)
    private String auditTag;

    @Column(nullable = false)
    private Integer maxUses;

    @Column(nullable = false)
    private Integer usedCount;

    @Column(length = 32, nullable = false)
    private String status;

    private LocalDateTime expiresAt;

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
