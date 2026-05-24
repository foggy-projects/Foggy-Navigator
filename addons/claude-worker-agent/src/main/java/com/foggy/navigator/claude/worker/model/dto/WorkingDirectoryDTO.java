package com.foggy.navigator.claude.worker.model.dto;

import com.foggy.navigator.common.dto.DirectoryMilestoneDTO;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作目录 DTO
 */
@Data
@Builder
public class WorkingDirectoryDTO {
    private String directoryId;
    private String workerId;
    private String projectName;
    /** 关联的 CodingAgent 实体 ID（项目级 Agent 身份，可 null） */
    private String agentId;
    /** Agent 名称（方便前端展示，可 null） */
    private String agentName;
    private ResourceOwnerType ownerType;
    private String ownerId;
    private String clientAppId;
    private String upstreamUserId;
    private WorkspaceScope workspaceScope;
    private WorkingDirectoryResolverType resolverType;
    private String rootRef;
    private String resolverKey;
    private Boolean readOnly;
    private List<String> allowedPathPrefixes;
    private String quotaJson;
    private String retentionPolicyJson;
    private String concurrencyPolicyJson;
    private Boolean enabled;
    private String path;
    private String gitBranch;
    private String gitRemoteUrl;
    private String gitProvider;
    private String gitStatus;
    private String agentTeamsConfig;
    private String directoryType;
    private String parentProjectId;
    private String projectTaskPrompt;
    private Boolean worktree;
    private String sourceDirectoryId;
    private String defaultAuthMode;
    private boolean defaultAuthConfigured;
    private String defaultBaseUrl;
    private String maskedDefaultAuthToken;
    private String defaultModelConfigId;
    private List<DirectoryMilestoneDTO> milestones;
    private List<WorkingDirectoryDTO> children;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
