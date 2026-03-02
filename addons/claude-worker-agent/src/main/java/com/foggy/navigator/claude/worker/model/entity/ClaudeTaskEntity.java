package com.foggy.navigator.claude.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Claude 任务记录
 */
@Data
@Entity
@Table(name = "claude_tasks", indexes = {
    @Index(name = "idx_ct_session_id", columnList = "sessionId"),
    @Index(name = "idx_ct_worker_id", columnList = "workerId"),
    @Index(name = "idx_ct_user_id", columnList = "userId"),
    @Index(name = "idx_ct_directory_id", columnList = "directoryId")
})
public class ClaudeTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String taskId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String prompt;

    @Column(length = 512)
    private String cwd;

    @Column(length = 64)
    private String directoryId;

    @Column(length = 32)
    private String status;

    @Column(length = 128)
    private String claudeSessionId;

    @Column(precision = 10, scale = 4)
    private BigDecimal costUsd;

    private Long inputTokens;

    private Long outputTokens;

    private Long durationMs;

    private Integer numTurns;

    @Column(length = 64)
    private String model;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** JSON array of checkpoint objects: [{"id":"uuid","turnIndex":1,"timestamp":"..."}] */
    @Column(columnDefinition = "TEXT")
    private String checkpoints;

    /** Whether file checkpointing was enabled for this task (Navigator-created tasks = true, synced = false) */
    @Column
    private Boolean fileCheckpointingEnabled;

    /** Task source: "PLATFORM" (created via Navigator) or "SYNCED" (imported from local Claude Code) */
    @Column(length = 32)
    private String source;

    /**
     * Reconciler 最后一次确认 CLI 进程存活的时间。
     * 超时检查用此字段替代 createdAt，保证真正运行中的任务不会被误判超时。
     * null 表示从未被 reconciler 确认过（任务刚创建或 reconciler 尚未运行）。
     */
    @Column
    private LocalDateTime lastAliveAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
