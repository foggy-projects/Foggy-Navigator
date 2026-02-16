package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

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
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
