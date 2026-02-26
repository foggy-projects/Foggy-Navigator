package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 编程 Agent 注册中心
 * Agent 是独立于 Worker/目录的逻辑实体，代表一个可接收任务的"编程 Agent"。
 * 对齐 A2A 协议的 Agent Card 概念。
 */
@Data
@Entity
@Table(name = "coding_agents", indexes = {
    @Index(name = "idx_ca_user_id", columnList = "userId"),
    @Index(name = "idx_ca_worker_id", columnList = "workerId")
})
public class CodingAgentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String agentId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64)
    private String tenantId;

    /** Agent 名称, e.g. "payment-service-agent" */
    @Column(length = 128, nullable = false)
    private String name;

    /** 能力描述 (A2A: Agent Card description) */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** LOCAL_CLAUDE_WORKER | EXTERNAL_A2A */
    @Column(length = 32, nullable = false)
    private String agentType;

    /** FK -> ClaudeWorkerEntity (本地 agent 必填) */
    @Column(length = 64)
    private String workerId;

    /** FK -> WorkingDirectoryEntity (默认工作目录，接收任务/创建 worktree) */
    @Column(length = 64)
    private String defaultDirectoryId;

    /** A2A endpoint URL (未来扩展) */
    @Column(length = 512)
    private String endpointUrl;

    /** A2A auth JSON (未来扩展) */
    @Column(columnDefinition = "TEXT")
    private String authScheme;

    /** JSON array of skill objects (A2A skills) */
    @Column(columnDefinition = "TEXT")
    private String skills;

    /** 默认拉代码的分支 (如 "dev", "main") */
    @Column(length = 128)
    private String defaultBranch;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (agentType == null) {
            agentType = "LOCAL_CLAUDE_WORKER";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
