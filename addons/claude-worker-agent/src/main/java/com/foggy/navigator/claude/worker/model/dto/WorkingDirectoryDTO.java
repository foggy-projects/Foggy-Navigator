package com.foggy.navigator.claude.worker.model.dto;

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
    private List<WorkingDirectoryDTO> children;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
