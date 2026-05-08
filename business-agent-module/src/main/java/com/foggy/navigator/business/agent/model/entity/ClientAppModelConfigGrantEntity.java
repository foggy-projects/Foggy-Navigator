package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "client_app_model_config_grant", uniqueConstraints = {
        @UniqueConstraint(name = "uk_client_app_model_config_grant", columnNames = {"clientAppId", "modelConfigId"})
}, indexes = {
        @Index(name = "idx_camcg_client_app", columnList = "clientAppId"),
        @Index(name = "idx_camcg_status", columnList = "status")
})
public class ClientAppModelConfigGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String clientAppId;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String modelConfigId;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(nullable = false)
    private Boolean isDefault;

    @Column(length = 64)
    private String grantScope;

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
