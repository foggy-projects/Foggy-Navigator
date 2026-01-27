package com.foggy.navigator.common.entity;

import com.foggy.navigator.common.enums.UserStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体
 */
@Data
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_username", columnList = "username", unique = true),
    @Index(name = "idx_email", columnList = "email"),
    @Index(name = "idx_tenant_id", columnList = "tenantId"),
    @Index(name = "idx_status", columnList = "status")
})
public class UserEntity {

    @Id
    @Column(length = 64)
    private String id;

    /**
     * 租户ID（可选，超级管理员可以为空）
     */
    @Column(length = 64)
    private String tenantId;

    /**
     * 用户名（全局唯一）
     */
    @Column(length = 128, nullable = false, unique = true)
    private String username;

    /**
     * 密码哈希值（BCrypt）
     */
    @Column(length = 512, nullable = false)
    private String passwordHash;

    /**
     * 邮箱
     */
    @Column(length = 128)
    private String email;

    /**
     * 显示名称
     */
    @Column(length = 128)
    private String displayName;

    /**
     * 角色列表（逗号分隔）如：TENANT_ADMIN,DEVELOPER
     */
    @Column(length = 256, nullable = false)
    private String roles;

    /**
     * 用户状态
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private UserStatus status;

    /**
     * 最后登录时间
     */
    private LocalDateTime lastLoginAt;

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
        if (status == null) {
            status = UserStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
