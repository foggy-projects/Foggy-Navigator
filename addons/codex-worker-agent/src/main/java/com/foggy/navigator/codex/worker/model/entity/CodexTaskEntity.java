package com.foggy.navigator.codex.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
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

    /** Immutable runtime affinity selected before the task is accepted. */
    @Column(length = 128)
    private String runtimeId;

    private Integer runtimeRevision;

    @Column(length = 32)
    private String runtimeType;

    @Column(length = 128)
    private String runtimeInstanceId;

    private Long routingEpoch;

    /** PREPARED | ACCEPTING | ACCEPTED | SUBSCRIBED | COMMITTED | UNKNOWN | ABORT_REQUESTED | ABORTED_BEFORE_ACCEPT | TERMINAL | DELETE_REQUESTED */
    @Column(length = 32)
    private String runtimeAcceptanceState;

    @Column(length = 64)
    private String runtimeRequestHash;

    /** Encrypted exact request envelope used for idempotent acceptance recovery. */
    @Column(columnDefinition = "LONGTEXT")
    private String runtimeRequestCiphertext;

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

    /** 前端解析的 CodingAgent 实体 ID（不持久化，仅用于 sync 到 SessionTaskEntity） */
    @Transient
    private String resolvedAgentId;

    /** OpenAPI/A2A contextId（不持久化到 codex_tasks，sync 到统一 SessionTask task_state_json） */
    @Transient
    private String contextId;

    /** TaskQueryProvider route（不持久化到 codex_tasks，由统一 Session/Task 投影持久化） */
    @Transient
    private String providerType;

    /** CodexBiz scoped CODEX_HOME logical key（不持久化到 codex_tasks，写入 SessionEntity.providerStateJson）。 */
    @Transient
    private String codexHomeKey;

    /** CodexBiz upstream account alias（不持久化到 codex_tasks，写入 SessionEntity.providerStateJson）。 */
    @Transient
    private String privateAccountId;

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

    private LocalDateTime lastOutputAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** UTC epoch captured at insert time. Legacy rows remain null and must not be inferred from LocalDateTime. */
    @Column(name = "created_at_epoch_ms", updatable = false)
    private Long createdAtEpochMs;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        createdAtEpochMs = Instant.now().toEpochMilli();
        updatedAt = LocalDateTime.now();
        if (lastOutputAt == null) {
            lastOutputAt = createdAt;
        }
        if (status == null) {
            status = "PENDING";
        }
        if (source == null) {
            source = "PLATFORM";
        }
        if (runtimeType == null) {
            runtimeType = "SDK_EXEC";
        }
        if (runtimeAcceptanceState == null && "APP_SERVER".equals(runtimeType)) {
            runtimeAcceptanceState = "PREPARED";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
