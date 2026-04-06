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
    @Index(name = "idx_ct_directory_id", columnList = "directoryId"),
    @Index(name = "idx_ct_dedup_key", columnList = "dedupKey")
})
public class ClaudeTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String taskId;

    /** claude-agent-worker 内部 task_id，用于 /subscribe /status /abort */
    @Column(length = 128)
    private String workerTaskId;

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

    /** 逻辑 CodingAgent ID（不持久化，仅用于同步统一 session/task 镜像） */
    @Transient
    private String resolvedAgentId;

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

    /** 创建任务时使用的平台 LLM 模型配置 ID */
    @Column(length = 64)
    private String modelConfigId;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** A2A 异步任务完成后保存的结果文本（轮询 getTask 时返回） */
    @Column(columnDefinition = "TEXT")
    private String resultText;

    /** A2A 多轮会话标识（Open API contextId） */
    @Column(length = 64)
    private String contextId;

    /** A2A 幂等去重键：hash(userId + agentId + prompt)，防止网络重试导致重复创建任务 */
    @Column(length = 64)
    private String dedupKey;

    /** JSON array of checkpoint objects: [{"id":"uuid","turnIndex":1,"timestamp":"..."}] */
    @Column(columnDefinition = "TEXT")
    private String checkpoints;

    /** 已确认收到的最新 Worker 事件序号（ack_seq） */
    private Integer lastAckedSeq;

    /** Whether file checkpointing was enabled for this task (Navigator-created tasks = true, synced = false) */
    @Column
    private Boolean fileCheckpointingEnabled;

    /** Task source: "PLATFORM" (created via Navigator) or "SYNCED" (imported from local Claude Code) */
    @Column(length = 32)
    private String source;

    /** Agent Teams 配置 ID（任务创建时锁定，不可变更） */
    @Column(length = 64)
    private String agentTeamsConfigId;

    /**
     * 中止请求标记 — 解决 cancel 线程与 SSE reactor 线程的并发死锁。
     * <p>
     * cancel 线程先置 true（commit），再通知 Worker；
     * Worker 回复 SSE error 后，failTask() 检查此标记 → 跳过 DB 更新，
     * 由 cancel 线程统一落库 ABORTED 并清除标记。
     * <p>
     * Worker 离线时此标记留存，Reconciler 可据此在 Worker 重连后重试 abort。
     */
    @Column
    private Boolean abortRequested;

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
