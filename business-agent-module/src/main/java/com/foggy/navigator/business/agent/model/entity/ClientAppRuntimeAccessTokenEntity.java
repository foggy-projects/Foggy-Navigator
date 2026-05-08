package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "client_app_runtime_access_token", indexes = {
        @Index(name = "idx_carat_client_app", columnList = "clientAppId"),
        @Index(name = "idx_carat_tenant", columnList = "tenantId"),
        @Index(name = "idx_carat_app_key", columnList = "appKey"),
        @Index(name = "idx_carat_status", columnList = "status")
})
public class ClientAppRuntimeAccessTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String tokenId;

    @Column(length = 128, nullable = false, unique = true)
    private String tokenHash;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String clientAppId;

    @Column(length = 64, nullable = false)
    private String credentialId;

    @Column(length = 96, nullable = false)
    private String appKey;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime revokedAt;

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
