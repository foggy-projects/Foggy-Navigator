package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "client_app_control_credential", indexes = {
        @Index(name = "idx_cacc_client_app", columnList = "clientAppId"),
        @Index(name = "idx_cacc_tenant", columnList = "tenantId"),
        @Index(name = "idx_cacc_status", columnList = "status")
})
public class ClientAppControlCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String credentialId;

    @Column(length = 64, nullable = false)
    private String clientAppId;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 128, nullable = false, unique = true)
    private String controlKeyHash;

    @Column(length = 64)
    private String issuedByUserId;

    @Column(length = 64)
    private String effectiveUserId;

    @Column(columnDefinition = "TEXT")
    private String scopes;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String description;

    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedAt;

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
