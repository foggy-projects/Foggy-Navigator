package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 模型授权绑定。
 *
 * <p>ClientApp / UpstreamSystem 决定模型配置的可见性，AgentModelBinding 决定某个 Agent
 * 是否允许在运行时使用该模型。Agent 的 defaultModelConfigId 视为隐式绑定。</p>
 */
@Data
@Entity
@Table(name = "coding_agent_models", indexes = {
    @Index(name = "idx_cam_agent_id", columnList = "tenantId,agentId"),
    @Index(name = "idx_cam_model_config_id", columnList = "tenantId,modelConfigId")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_cam_tenant_agent_model", columnNames = {"tenantId", "agentId", "modelConfigId"})
})
public class AgentModelBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String tenantId;

    /** FK -> CodingAgentEntity.agentId */
    @Column(length = 64, nullable = false)
    private String agentId;

    /** FK -> LlmModelConfigDTO.modelConfigId */
    @Column(length = 64, nullable = false)
    private String modelConfigId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
