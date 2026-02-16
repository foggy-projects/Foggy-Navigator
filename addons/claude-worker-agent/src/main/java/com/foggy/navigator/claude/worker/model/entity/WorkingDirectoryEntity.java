package com.foggy.navigator.claude.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Worker 工作目录
 */
@Data
@Entity
@Table(name = "claude_working_directories", indexes = {
    @Index(name = "idx_cwd_worker_id", columnList = "workerId"),
    @Index(name = "idx_cwd_user_id", columnList = "userId")
})
public class WorkingDirectoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String directoryId;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64)
    private String tenantId;

    @Column(length = 128, nullable = false)
    private String projectName;

    @Column(length = 512, nullable = false)
    private String path;

    @Column(length = 128)
    private String gitBranch;

    @Column(length = 512)
    private String gitRemoteUrl;

    @Column(length = 32)
    private String gitProvider;

    @Column(length = 32)
    private String gitStatus;

    @Column(columnDefinition = "TEXT")
    private String agentTeamsConfig;

    private LocalDateTime lastSyncedAt;

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
