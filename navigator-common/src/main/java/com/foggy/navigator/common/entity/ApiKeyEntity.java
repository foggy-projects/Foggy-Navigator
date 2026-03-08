package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API Key 实体
 */
@Data
@Entity
@Table(name = "api_keys", indexes = {
    @Index(name = "idx_api_key", columnList = "apiKey", unique = true),
    @Index(name = "idx_user_id", columnList = "userId"),
    @Index(name = "idx_expires_at", columnList = "expiresAt")
})
public class ApiKeyEntity {

    @Id
    @Column(length = 64)
    private String id;

    /**
     * 所属用户ID
     */
    @Column(length = 64, nullable = false)
    private String userId;

    /**
     * API Key（加密存储）
     */
    @Column(length = 128, nullable = false, unique = true)
    private String apiKey;

    /**
     * API Key 名称/描述
     */
    @Column(length = 256)
    private String name;

    /**
     * 是否启用
     */
    @Column(nullable = false)
    private Boolean enabled;

    /**
     * 过期时间（可选）
     */
    private LocalDateTime expiresAt;

    /**
     * 最后使用时间
     */
    private LocalDateTime lastUsedAt;

    /**
     * 创建时间
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enabled == null) {
            enabled = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
