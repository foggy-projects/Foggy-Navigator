package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;

public class AgentWorkspaceBindingDTO {

    private Long id;
    private String tenantId;
    private String agentId;
    private String directoryId;
    private String clientAppId;
    private String projectName;
    private String rootRef;
    private String path;
    private String workspaceScope;
    private String directoryOwnerType;
    private String directoryOwnerId;
    private Boolean defaultDirectory;
    private Boolean enabled;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getDirectoryId() {
        return directoryId;
    }

    public void setDirectoryId(String directoryId) {
        this.directoryId = directoryId;
    }

    public String getClientAppId() {
        return clientAppId;
    }

    public void setClientAppId(String clientAppId) {
        this.clientAppId = clientAppId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getRootRef() {
        return rootRef;
    }

    public void setRootRef(String rootRef) {
        this.rootRef = rootRef;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getWorkspaceScope() {
        return workspaceScope;
    }

    public void setWorkspaceScope(String workspaceScope) {
        this.workspaceScope = workspaceScope;
    }

    public String getDirectoryOwnerType() {
        return directoryOwnerType;
    }

    public void setDirectoryOwnerType(String directoryOwnerType) {
        this.directoryOwnerType = directoryOwnerType;
    }

    public String getDirectoryOwnerId() {
        return directoryOwnerId;
    }

    public void setDirectoryOwnerId(String directoryOwnerId) {
        this.directoryOwnerId = directoryOwnerId;
    }

    public Boolean getDefaultDirectory() {
        return defaultDirectory;
    }

    public void setDefaultDirectory(Boolean defaultDirectory) {
        this.defaultDirectory = defaultDirectory;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
