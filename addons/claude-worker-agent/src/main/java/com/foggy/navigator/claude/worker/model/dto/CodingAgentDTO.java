package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 编程 Agent DTO
 */
@Data
@Builder
public class CodingAgentDTO {
    private String agentId;
    private String name;
    private String description;
    private String agentType;
    private String workerId;
    private String workerName;
    private String defaultDirectoryId;
    private String skills;
    private String defaultBranch;
    private String projectSummary;

    /** 默认目录信息 */
    private DirectorySummary defaultDirectory;

    /** 授权的目录列表 */
    private List<DirectorySummary> authorizedDirectories;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    public static class DirectorySummary {
        private String directoryId;
        private String projectName;
        private String path;
        private String gitBranch;
    }
}
