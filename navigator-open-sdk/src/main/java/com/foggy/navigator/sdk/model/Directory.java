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
    private String ownerType;
    private String ownerId;
    private String clientAppId;
    private String upstreamUserId;
    private String workspaceScope;
    private String resolverType;
    private String rootRef;
    private String resolverKey;
    private Boolean readOnly;
    private Boolean enabled;
    private String path;
    private String directoryType;
    private String gitBranch;
    private String gitRemoteUrl;
    private String defaultModelConfigId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getDirectoryId() { return directoryId; }
    public void setDirectoryId(String directoryId) { this.directoryId = directoryId; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getUpstreamUserId() { return upstreamUserId; }
    public void setUpstreamUserId(String upstreamUserId) { this.upstreamUserId = upstreamUserId; }
    public String getWorkspaceScope() { return workspaceScope; }
    public void setWorkspaceScope(String workspaceScope) { this.workspaceScope = workspaceScope; }
    public String getResolverType() { return resolverType; }
    public void setResolverType(String resolverType) { this.resolverType = resolverType; }
    public String getRootRef() { return rootRef; }
    public void setRootRef(String rootRef) { this.rootRef = rootRef; }
    public String getResolverKey() { return resolverKey; }
    public void setResolverKey(String resolverKey) { this.resolverKey = resolverKey; }
    public Boolean getReadOnly() { return readOnly; }
    public void setReadOnly(Boolean readOnly) { this.readOnly = readOnly; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getDirectoryType() { return directoryType; }
    public void setDirectoryType(String directoryType) { this.directoryType = directoryType; }
    public String getGitBranch() { return gitBranch; }
    public void setGitBranch(String gitBranch) { this.gitBranch = gitBranch; }
    public String getGitRemoteUrl() { return gitRemoteUrl; }
    public void setGitRemoteUrl(String gitRemoteUrl) { this.gitRemoteUrl = gitRemoteUrl; }
    public String getDefaultModelConfigId() { return defaultModelConfigId; }
    public void setDefaultModelConfigId(String defaultModelConfigId) { this.defaultModelConfigId = defaultModelConfigId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "Directory{directoryId='" + directoryId + "', path='" + path + "'}";
    }
}
