package com.foggy.navigator.coding.agent.api.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conversations")
public class ConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String conversationId;

    @Column(length = 64)
    private String sandboxId;

    // 关联到 session-module 的 SessionEntity
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(length = 128)
    private String ohConversationId;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 64)
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConversationStatus status;

    @Column(length = 128)
    private String namespace;

    // Git 凭证 ID（关联 GitCredentialEntity）
    @Column(length = 64)
    private String gitCredentialId;

    // Git 服务提供商
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private GitProvider gitProvider;

    // Git 项目 ID（GitLab 数字ID 或 GitHub owner/repo）
    @Column(length = 256)
    private String gitProjectId;

    // Git 项目路径（如 namespace/project-name）
    @Column(length = 256)
    private String gitProjectPath;

    // Git 仓库 clone URL
    @Column(length = 512)
    private String gitRepoUrl;

    // 用户选择的基准分支（如 main, develop）
    @Column(length = 64)
    private String baseBranch;

    // 系统创建的工作分支（如 coding-agent/fix-login-20240203-143052）
    @Column(length = 128)
    private String workingBranch;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum ConversationStatus {
        STARTING,
        WAITING_FOR_SANDBOX,
        PREPARING_REPOSITORY,
        READY,
        RUNNING,
        IDLE,
        PAUSED,
        ERROR,
        STOPPED
    }
}
