package com.foggy.navigator.claude.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跨项目任务
 */
@Data
@Entity
@Table(name = "claude_cross_project_tasks", indexes = {
    @Index(name = "idx_cxpt_user_id", columnList = "userId"),
    @Index(name = "idx_cxpt_status", columnList = "status"),
    @Index(name = "idx_cxpt_initial_session", columnList = "initialSessionId")
})
public class CrossProjectTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** A2A: contextId */
    @Column(length = 64, nullable = false, unique = true)
    private String contextId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64)
    private String tenantId;

    @Column(length = 256, nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** DRAFT | RUNNING | PAUSED | COMPLETED | FAILED | CANCELLED */
    @Column(length = 32, nullable = false)
    private String status;

    /** 当前活跃 phase (0-based) */
    private Integer currentPhaseIndex;

    private Integer totalPhases;

    /** SEQUENTIAL (Phase 1), 未来: DAG */
    @Column(length = 32)
    private String executionMode;

    @Column(precision = 10, scale = 4)
    private BigDecimal totalCostUsd;

    /** 创建此任务的"初始会话" sessionId */
    @Column(length = 64)
    private String initialSessionId;

    /** 初始会话所在的工作目录 */
    @Column(length = 64)
    private String initialDirectoryId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "DRAFT";
        }
        if (executionMode == null) {
            executionMode = "SEQUENTIAL";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
