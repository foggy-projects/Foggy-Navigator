package com.foggy.navigator.common.entity;

import com.foggy.navigator.common.enums.ConfigItemStatus;
import com.foggy.navigator.common.enums.GitAuthType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 语义层配置 JPA Entity
 */
@Data
@Entity
@Table(name = "semantic_layer_configs", indexes = {
    @Index(name = "idx_sl_tenant_id", columnList = "tenantId"),
    @Index(name = "idx_sl_datasource_id", columnList = "datasourceId"),
    @Index(name = "idx_sl_status", columnList = "status")
})
public class SemanticLayerConfigEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64)
    private String tenantId;

    /**
     * 关联的数据源ID
     */
    @Column(length = 64, nullable = false)
    private String datasourceId;

    /**
     * Git仓库URL
     */
    @Column(length = 512)
    private String gitRepoUrl;

    /**
     * Git分支
     */
    @Column(length = 128)
    private String gitBranch;

    /**
     * Git认证方式
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private GitAuthType gitAuthType;

    /**
     * 访问令牌（加密存储）
     */
    @Column(length = 512)
    private String gitAccessToken;

    /**
     * Git用户名（BASIC认证）
     */
    @Column(length = 128)
    private String gitUsername;

    /**
     * Git密码（加密存储）
     */
    @Column(length = 512)
    private String gitPassword;

    /**
     * 语义层根路径
     */
    @Column(length = 512)
    private String semanticLayerPath;

    /**
     * TM模型路径
     */
    @Column(length = 256)
    private String modelsPath;

    /**
     * QM查询路径
     */
    @Column(length = 256)
    private String queriesPath;

    /**
     * 是否自动同步
     */
    private Boolean autoSync;

    /**
     * 同步间隔（分钟）
     */
    private Integer syncInterval;

    /**
     * 模型数量
     */
    private Integer modelCount;

    /**
     * 配置状态
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private ConfigItemStatus status;

    /**
     * 最后验证时间
     */
    private LocalDateTime lastValidatedAt;

    /**
     * 配置描述
     */
    @Column(columnDefinition = "TEXT")
    private String description;

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
            status = ConfigItemStatus.NOT_STARTED;
        }
        if (gitBranch == null) {
            gitBranch = "main";
        }
        if (modelCount == null) {
            modelCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
