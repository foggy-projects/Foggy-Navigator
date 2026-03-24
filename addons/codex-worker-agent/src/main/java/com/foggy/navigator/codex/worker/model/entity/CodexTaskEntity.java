package com.foggy.navigator.codex.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Codex 任务记录
 */
@Data
@Entity
@Table(name = "codex_tasks", indexes = {
    @Index(name = "idx_cxt_user_id", columnList = "userId"),
    @Index(name = "idx_cxt_worker_id", columnList = "workerId"),
    @Index(name = "idx_cxt_status", columnList = "status")
})
public class CodexTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String taskId;

    /** Upstream codex-agent-worker task_id，用于 /subscribe /status /abort */
    @Column(length = 128)
    private String workerTaskId;

    /** Foggy session ID */
    @Column(length = 64)
    private String sessionId;

    /** FK -> WorkingDirectoryEntity (optional, for directory filtering) */
    @Column(length = 64)
    private String directoryId;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64)
    private String tenantId;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(length = 512)
    private String cwd;

    /** PENDING | RUNNING | COMPLETED | FAILED | ABORTED */
    @Column(length = 32, nullable = false)
    private String status;

    /** Codex SDK thread ID (for session resume) */
    @Column(length = 256)
    private String codexThreadId;

    @Column(length = 128)
    private String model;

    @Column(precision = 10, scale = 6)
    private BigDecimal costUsd;

    private Long inputTokens;
    private Long outputTokens;
    private Long durationMs;
    private Integer numTurns;

    @Column(columnDefinition = "TEXT")
    private String resultText;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** 已确认收到的最新 Worker 事件序号（ack_seq） */
    private Integer lastAckedSeq;

    /** PLATFORM (created by Navigator) | SYNCED (discovered from Codex sessions) */
    @Column(length = 32)
    private String source;

    private LocalDateTime lastAliveAt;

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
        if (source == null) {
            source = "PLATFORM";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
