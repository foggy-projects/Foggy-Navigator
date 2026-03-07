package com.foggy.navigator.common.entity;

import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ModelAccessScope;
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
     * 模型名称，如 qwen-max
     */
    @Column(length = 100, nullable = false)
    private String modelName;

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
     * 访问范围：GLOBAL（所有 Worker 可用）/ RESTRICTED（仅指定 Worker 可用）
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ModelAccessScope scope;

    /**
     * 创建时间
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 排序序号（越小越靠前）
     */
    @Column(nullable = false)
    private Integer sortOrder;

    /**
     * Worker 后端类型
     * CLAUDE_CODE = 由 Claude Worker 执行
     * OPENAI_CODEX = 由 Codex Worker 执行
     * null = 普通 LLM 配置（Tutor/Agent Framework 用，不涉及 Worker 执行）
     */
    @Column(length = 32)
    private String workerBackend;

    /**
     * 环境变量（JSON 格式存储，如 {"CLAUDE_AUTOCOMPACT_PCT_OVERRIDE":"80"}）
     * 使用该模型启动 Claude Code 时注入到 CLI 子进程
     */
    @Column(columnDefinition = "TEXT")
    private String envVars;

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
        if (scope == null) {
            scope = ModelAccessScope.GLOBAL;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
