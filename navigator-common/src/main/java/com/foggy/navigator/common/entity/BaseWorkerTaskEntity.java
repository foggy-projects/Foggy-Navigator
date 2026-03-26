package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Worker 任务 Entity 基类 —— 新 Agent 的 Task Entity 应继承此类。
 * <p>
 * 包含所有 Agent 共用的任务字段（与 DispatchTaskDTO 对齐）。
 * Provider 特有数据通过 {@link #providerExt} JSON 列存储（组合方式），
 * 避免每个新 Agent 重复定义 20+ 个公共列。
 * <p>
 * 使用示例：
 * <pre>
 * {@literal @}Entity
 * {@literal @}Table(name = "your_agent_tasks")
 * public class YourTaskEntity extends BaseWorkerTaskEntity {
 *     // 仅需定义 Entity 级别的特殊配置（如额外索引）
 *     // Provider 特有数据放入 providerExt JSON
 * }
 * </pre>
 * <p>
 * 注意：现有 ClaudeTaskEntity / CodexTaskEntity 未继承此类（历史原因），
 * 它们的公共字段与此基类完全一致，但特有字段仍作为独立列保留。
 */
@Data
@MappedSuperclass
public abstract class BaseWorkerTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, unique = true, nullable = false)
    private String taskId;

    /** Worker 上游任务 ID */
    @Column(length = 128)
    private String workerTaskId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 64)
    private String directoryId;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64)
    private String tenantId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String prompt;

    @Column(length = 512)
    private String cwd;

    @Column(length = 32)
    private String status;

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

    private Integer lastAckedSeq;

    @Column(length = 32)
    private String source;

    private LocalDateTime lastAliveAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Provider 特有扩展数据（JSON 格式）。
     * <p>
     * 新 Agent 的特有字段统一存放在此列，无需为每个字段单独加列。
     * 例如: {"threadId": "xxx", "customField": 123}
     */
    @Column(columnDefinition = "JSON")
    private String providerExt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
