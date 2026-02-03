package com.foggy.navigator.common.entity;

import com.foggy.navigator.common.enums.SkillScope;
import com.foggy.navigator.common.enums.SkillStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 配置 JPA Entity
 */
@Data
@Entity
@Table(name = "skill_configs", indexes = {
    @Index(name = "idx_skill_tenant_id", columnList = "tenantId"),
    @Index(name = "idx_skill_scope", columnList = "scope"),
    @Index(name = "idx_skill_agent_id", columnList = "agentId"),
    @Index(name = "idx_skill_status", columnList = "status"),
    @Index(name = "idx_skill_name", columnList = "name")
})
public class SkillConfigEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64)
    private String tenantId;

    /**
     * Skill 作用域
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private SkillScope scope;

    /**
     * Agent ID（AGENT作用域时必填）
     */
    @Column(length = 64)
    private String agentId;

    /**
     * Skill 名称
     */
    @Column(length = 128, nullable = false)
    private String name;

    /**
     * Skill 描述
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 触发关键词（JSON数组）
     */
    @Column(columnDefinition = "TEXT")
    private String triggerKeywords;

    /**
     * 意图列表（JSON数组）
     */
    @Column(columnDefinition = "TEXT")
    private String intents;

    /**
     * 执行逻辑
     */
    @Column(columnDefinition = "TEXT")
    private String executionLogic;

    /**
     * 输出格式
     */
    @Column(columnDefinition = "TEXT")
    private String outputFormat;

    /**
     * 分派条件
     */
    @Column(columnDefinition = "TEXT")
    private String delegationCondition;

    /**
     * 完整 Markdown 内容
     */
    @Column(columnDefinition = "LONGTEXT")
    private String markdownContent;

    /**
     * Skill 状态
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private SkillStatus status;

    /**
     * 优先级（数值越小优先级越高）
     */
    private Integer priority;

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
            status = SkillStatus.DRAFT;
        }
        if (scope == null) {
            scope = SkillScope.GLOBAL;
        }
        if (priority == null) {
            priority = 100;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
