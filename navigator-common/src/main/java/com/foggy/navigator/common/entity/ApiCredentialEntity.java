package com.foggy.navigator.common.entity;

import com.foggy.navigator.common.enums.AuthType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API 凭证实体
 */
@Data
@Entity
@Table(name = "api_credential", indexes = {
    @Index(name = "idx_credential_tenant_id", columnList = "tenantId"),
    @Index(name = "idx_credential_category", columnList = "category"),
    @Index(name = "idx_credential_name", columnList = "name")
})
public class ApiCredentialEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(length = 50, nullable = false)
    private String category;

    @Column(length = 255)
    private String baseUrl;

    @Column(length = 512, nullable = false)
    private String apiKey;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AuthType authType;

    @Column(length = 100)
    private String authHeaderName;

    @Column(length = 1000)
    private String extraHeaders;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Boolean isActive;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isActive == null) {
            isActive = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
