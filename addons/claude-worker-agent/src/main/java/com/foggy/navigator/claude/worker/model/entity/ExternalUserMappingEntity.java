package com.foggy.navigator.claude.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外部用户映射 Entity
 * 映射第三方系统的员工 ID 到 Navigator 内部用户 ID
 */
@Data
@Entity
@Table(name = "external_user_mappings", indexes = {
        @Index(name = "idx_eum_tenant_ext", columnList = "tenantId, externalUserId", unique = true),
        @Index(name = "idx_eum_user_id", columnList = "userId")
})
public class ExternalUserMappingEntity {

    @Id
    @Column(length = 64)
    private String id;

    /** 租户 ID（来自调用者 API Key 绑定的用户） */
    @Column(length = 64, nullable = false)
    private String tenantId;

    /** 第三方系统的员工/用户 ID */
    @Column(length = 128, nullable = false)
    private String externalUserId;

    /** 第三方系统的员工显示名 */
    @Column(length = 256)
    private String externalDisplayName;

    /** 映射的 Navigator 内部用户 ID */
    @Column(length = 64, nullable = false)
    private String userId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
