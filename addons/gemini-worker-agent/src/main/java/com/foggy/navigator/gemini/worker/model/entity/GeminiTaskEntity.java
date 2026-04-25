package com.foggy.navigator.gemini.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Gemini 任务记录
 */
@Data
@Entity
@Table(name = "gemini_tasks", indexes = {
        @Index(name = "idx_gmt_user_id", columnList = "userId"),
        @Index(name = "idx_gmt_worker_id", columnList = "workerId"),
        @Index(name = "idx_gmt_status", columnList = "status")
})
public class GeminiTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String taskId;

    @Column(length = 128)
    private String workerTaskId;

    @Column(length = 64)
    private String sessionId;

    @Column(length = 64)
    private String directoryId;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64)
    private String tenantId;

    @Transient
    private String resolvedAgentId;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(length = 512)
    private String cwd;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 256)
    private String geminiSessionId;

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
