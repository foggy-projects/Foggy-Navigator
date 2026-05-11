package com.foggy.navigator.langgraph.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "langgraph_tasks", indexes = {
        @Index(name = "idx_lgt_session_id", columnList = "sessionId"),
        @Index(name = "idx_lgt_worker_id", columnList = "workerId"),
        @Index(name = "idx_lgt_user_id", columnList = "userId"),
        @Index(name = "idx_lgt_directory_id", columnList = "directoryId")
})
public class LanggraphTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String taskId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 64)
    private String agentId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64)
    private String tenantId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String prompt;

    @Column(length = 32, nullable = false)
    private String status; // PENDING, RUNNING, COMPLETED, FAILED, ABORTED

    @Column(length = 64)
    private String model;

    @Column(length = 64)
    private String modelConfigId;

    @Column(length = 64)
    private String directoryId;

    @Column(length = 512)
    private String cwd;

    @Column(length = 64)
    private String contextId;

    @Column(columnDefinition = "TEXT")
    private String resultText;

    /** Structured output from Skill Runtime (JSON) */
    @Column(columnDefinition = "TEXT")
    private String structuredOutput;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Long durationMs;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
