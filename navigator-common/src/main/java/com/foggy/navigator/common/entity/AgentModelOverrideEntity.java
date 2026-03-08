package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 模型覆盖配置
 * 为特定 Agent 指定专属的 LLM 模型（覆盖该 category 的默认模型）
 */
@Data
@Entity
@Table(name = "agent_model_override", indexes = {
    @Index(name = "idx_amo_tenant_id", columnList = "tenantId"),
    @Index(name = "idx_amo_agent_id", columnList = "agentId")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_amo_tenant_agent", columnNames = {"tenantId", "agentId"})
})
public class AgentModelOverrideEntity {

    @Id
    @Column(length = 64)
    private String id;

    /**
     * 租户ID
     */
    @Column(length = 64, nullable = false)
    private String tenantId;

    /**
     * Agent ID，如 "coding-agent"
     */
    @Column(length = 50, nullable = false)
    private String agentId;

    /**
     * 引用的模型配置ID（llm_model_config.id）
     */
    @Column(length = 64, nullable = false)
    private String modelConfigId;

    /**
     * 创建时间
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
