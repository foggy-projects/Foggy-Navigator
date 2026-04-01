package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 统一会话任务流水。
 * 按 provider 保留最小公共列，特殊字段进入 taskStateJson。
 */
@Data
@Entity
@Table(name = "session_tasks", indexes = {
    @Index(name = "idx_st_task_id", columnList = "taskId", unique = true),
    @Index(name = "idx_st_session_id", columnList = "sessionId"),
    @Index(name = "idx_st_user_id", columnList = "userId"),
    @Index(name = "idx_st_worker_id", columnList = "workerId"),
    @Index(name = "idx_st_directory_id", columnList = "directoryId"),
    @Index(name = "idx_st_status", columnList = "status"),
    @Index(name = "idx_st_provider_type", columnList = "providerType"),
    @Index(name = "idx_st_agent_id", columnList = "agentId"),
    @Index(name = "idx_st_created_at", columnList = "createdAt")
})
public class SessionTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String taskId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 32, nullable = false)
    private String providerType;

    /** 上游 worker 侧 taskId。 */
    @Column(length = 128)
    private String providerTaskId;

    @Column(length = 64)
    private String workerId;

    @Column(length = 64)
    private String userId;

    @Column(length = 64)
    private String tenantId;

    @Column(length = 64)
    private String agentId;

    @Column(length = 64)
    private String directoryId;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(length = 512)
    private String cwd;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 128)
    private String model;

    /** 创建任务时使用的平台 LLM 模型配置 ID */
    @Column(length = 64)
    private String modelConfigId;

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

    @Column(length = 32)
    private String source;

    private Integer lastAckedSeq;

    private LocalDateTime lastAliveAt;

    @Column(columnDefinition = "TEXT")
    private String taskStateJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
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
