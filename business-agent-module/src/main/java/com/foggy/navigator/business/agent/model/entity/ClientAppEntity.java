package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "client_app", indexes = {
        @Index(name = "idx_client_app_tenant", columnList = "tenantId"),
        @Index(name = "idx_client_app_status", columnList = "status"),
        @Index(name = "idx_client_app_upstream", columnList = "upstreamSystemId,upstreamClientAppNamespace,tenantId,upstreamRef")
})
public class ClientAppEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String clientAppId;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 64)
    private String ownerUserId;

    @Column(length = 64)
    private String capabilityDomain;

    @Column(length = 128)
    private String upstreamSystemId;

    @Column(length = 128)
    private String upstreamClientAppNamespace;

    @Column(length = 128)
    private String upstreamRef;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 64)
    private String provisioningCredentialId;

    @Column(length = 64)
    private String createdBy;

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
