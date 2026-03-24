package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Worker 工作目录 —— Agent 无关，所有 Agent 共享。
 */
@Data
@Entity
@Table(name = "working_directories", indexes = {
    @Index(name = "idx_wd_worker_id", columnList = "workerId"),
    @Index(name = "idx_wd_user_id", columnList = "userId"),
    @Index(name = "idx_wd_parent_project", columnList = "parentProjectId")
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

    /** STANDARD | PROJECT */
    @Column(length = 32, nullable = false)
    private String directoryType = "STANDARD";

    /** 指向 PROJECT 类型目录的 directoryId */
    @Column(length = 64)
    private String parentProjectId;

    /** PROJECT 类型的任务分配 system prompt */
    @Column(columnDefinition = "TEXT")
    private String projectTaskPrompt;

    /** 是否为 git worktree 创建的临时目录 */
    @Column
    private Boolean worktree = false;

    /** worktree 来源目录的 directoryId */
    @Column(length = 64)
    private String sourceDirectoryId;

    /** 默认认证模式: API_KEY | CUSTOM_ENDPOINT | SUBSCRIPTION | null(未配置) */
    @Column(length = 32)
    private String defaultAuthMode;

    /** 默认认证 Token（加密存储） */
    @Column(columnDefinition = "TEXT")
    private String defaultAuthToken;

    /** 默认自定义端点 URL */
    @Column(length = 512)
    private String defaultBaseUrl;

    /** 平台 LLM 配置 ID（优先于手动 auth 配置） */
    @Column(length = 64)
    private String defaultModelConfigId;

    /** 自定义环境变量（JSON Map，注入到 CLI 进程） */
    @Column(columnDefinition = "TEXT")
    private String customEnvVars;

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
