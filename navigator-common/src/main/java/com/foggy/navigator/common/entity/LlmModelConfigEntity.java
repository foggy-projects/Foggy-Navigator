package com.foggy.navigator.common.entity;

import com.foggy.navigator.common.enums.LlmModelCategory;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 模型配置实体
 */
@Data
@Entity
@Table(name = "llm_model_config", indexes = {
    @Index(name = "idx_llm_tenant_id", columnList = "tenantId"),
    @Index(name = "idx_llm_category", columnList = "category"),
    @Index(name = "idx_llm_is_default", columnList = "isDefault")
})
public class LlmModelConfigEntity {

    @Id
    @Column(length = 64)
    private String id;

    /**
     * 租户ID
     */
    @Column(length = 64, nullable = false)
    private String tenantId;

    /**
     * 显示名称，如"通义千问-Max"
     */
    @Column(length = 100, nullable = false)
    private String name;

    /**
     * 模型类别
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private LlmModelCategory category;

    /**
     * API Base URL，如 https://dashscope.aliyuncs.com/compatible-mode/v1
     */
    @Column(length = 255, nullable = false)
    private String baseUrl;

    /**
     * 模型名称，如 qwen-max（主模型，默认为 opus）
     */
    @Column(length = 100)
    private String modelName;

    /**
     * Haiku 级别模型名称（用于简单任务）
     */
    @Column(length = 100)
    private String haikuModelName;

    /**
     * Sonnet 级别模型名称（用于中等复杂度任务）
     */
    @Column(length = 100)
    private String sonnetModelName;

    /**
     * Opus 级别模型名称（用于复杂任务，默认使用 modelName）
     */
    @Column(length = 100)
    private String opusModelName;

    /**
     * API Key（CredentialEncryptor 加密存储）
     */
    @Column(length = 512, nullable = false)
    private String apiKey;

    /**
     * 是否为该 category 的默认模型（每个 category + tenant 只有一个默认）
     */
    @Column(nullable = false)
    private Boolean isDefault;

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
        if (isDefault == null) {
            isDefault = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
