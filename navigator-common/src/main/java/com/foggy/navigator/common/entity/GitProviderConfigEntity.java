package com.foggy.navigator.common.entity;

import com.foggy.navigator.common.enums.GitProviderType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Git 提供者配置实体
 */
@Data
@Entity
@Table(name = "git_provider_config", indexes = {
    @Index(name = "idx_git_tenant_id", columnList = "tenantId"),
    @Index(name = "idx_git_provider_type", columnList = "providerType")
})
public class GitProviderConfigEntity {

    @Id
    @Column(length = 64)
    private String id;

    /**
     * 租户ID
     */
    @Column(length = 64, nullable = false)
    private String tenantId;

    /**
     * Git 提供者类型
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private GitProviderType providerType;

    /**
     * 服务地址（GitLab 私有部署时需填写，GitHub/Gitee 可空用默认值）
     */
    @Column(length = 255)
    private String baseUrl;

    /**
     * 访问令牌（CredentialEncryptor 加密存储）
     */
    @Column(length = 512, nullable = false)
    private String accessToken;

    /**
     * 用户名（可选）
     */
    @Column(length = 100)
    private String username;

    /**
     * 是否启用
     */
    @Column(nullable = false)
    private Boolean isActive;

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
        if (isActive == null) {
            isActive = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
