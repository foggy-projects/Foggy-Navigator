package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

/**
 * 工作目录
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Directory {
    private String directoryId;
    private String workerId;
    private String projectName;
    private String path;
    private String directoryType;
    private String gitBranch;
    private String gitRemoteUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getDirectoryId() { return directoryId; }
    public void setDirectoryId(String directoryId) { this.directoryId = directoryId; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getDirectoryType() { return directoryType; }
    public void setDirectoryType(String directoryType) { this.directoryType = directoryType; }
    public String getGitBranch() { return gitBranch; }
    public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }
    public String getGitRemoteUrl() { return gitRemoteUrl; }
    public void setGitRemoteUrl(String gitRemoteUrl) { this.gitRemoteUrl = gitRemoteUrl; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "Directory{directoryId='" + directoryId + "', path='" + path + "'}";
    }
}
