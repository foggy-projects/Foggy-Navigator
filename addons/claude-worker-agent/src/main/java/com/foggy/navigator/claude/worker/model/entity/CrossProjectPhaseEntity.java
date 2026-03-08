package com.foggy.navigator.claude.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跨项目任务阶段
 */
@Data
@Entity
@Table(name = "claude_cross_project_phases", indexes = {
    @Index(name = "idx_cxpp_context_id", columnList = "contextId"),
    @Index(name = "idx_cxpp_directory_id", columnList = "directoryId"),
    @Index(name = "idx_cxpp_claude_task_id", columnList = "claudeTaskId")
})
public class CrossProjectPhaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String phaseId;

    /** FK -> CrossProjectTaskEntity.contextId */
    @Column(length = 64, nullable = false)
    private String contextId;

    /** 执行顺序 (0-based) */
    @Column(nullable = false)
    private Integer phaseIndex;

    @Column(length = 256, nullable = false)
    private String phaseName;

    /** 本阶段的任务描述 */
    @Column(columnDefinition = "TEXT")
    private String prompt;

    /** FK -> CodingAgentEntity.agentId */
    @Column(length = 64)
    private String agentId;

    /** 目标工作目录 */
    @Column(length = 64)
    private String directoryId;

    /** 目标 Worker */
    @Column(length = 64)
    private String workerId;

    /** PENDING | RUNNING | AWAITING_REVIEW | COMPLETED | FAILED | SKIPPED */
    @Column(length = 32, nullable = false)
    private String status;

    /** FK -> ClaudeTaskEntity.taskId */
    @Column(length = 64)
    private String claudeTaskId;

    /** Phase 会话的 Foggy sessionId */
    @Column(length = 64)
    private String phaseSessionId;

    /** Phase 会话的 Claude Code sessionId */
    @Column(length = 128)
    private String claudeSessionId;

    /** 为此 phase 创建的 worktree 目录 ID */
    @Column(length = 64)
    private String worktreeDirectoryId;

    /** worktree 分支名 */
    @Column(length = 128)
    private String worktreeBranch;

    /** Agent 完成后写入的交接信息 (A2A: Artifact) */
    @Column(columnDefinition = "TEXT")
    private String handoffArtifact;

    /** 从上一 phase 注入的交接上下文 */
    @Column(columnDefinition = "TEXT")
    private String incomingContext;

    @Column(precision = 10, scale = 4)
    private BigDecimal costUsd;

    private Long durationMs;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
