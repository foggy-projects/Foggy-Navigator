package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

/**
 * Worker 信息
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Worker {
    private String workerId;
    private String name;
    private String baseUrl;
    private String authMode;
    private String status;
    private String hostname;
    private String workerVersion;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime createdAt;
    private String sshUsername;
    private Integer sshPort;
    private boolean sshPasswordConfigured;
    private String codeServerPublicUrl;
    private String codeServerInternalUrl;
    private boolean codeServerPasswordConfigured;
    private String codeServerFolderPrefix;

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAuthMode() { return authMode; }
    public void setAuthMode(String authMode) { this.authMode = authMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }
    public String getWorkerVersion() { return workerVersion; }
    public void setWorkerVersion(String workerVersion) { this.workerVersion = workerVersion; }
    public LocalDateTime getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(LocalDateTime lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getSshUsername() { return sshUsername; }
    public void setSshUsername(String sshUsername) { this.sshUsername = sshUsername; }
    public Integer getSshPort() { return sshPort; }
    public void setSshPort(Integer sshPort) { this.sshPort = sshPort; }
    public boolean isSshPasswordConfigured() { return sshPasswordConfigured; }
    public void setSshPasswordConfigured(boolean sshPasswordConfigured) { this.sshPasswordConfigured = sshPasswordConfigured; }
    public String getCodeServerPublicUrl() { return codeServerPublicUrl; }
    public void setCodeServerPublicUrl(String codeServerPublicUrl) { this.codeServerPublicUrl = codeServerPublicUrl; }
    public String getCodeServerInternalUrl() { return codeServerInternalUrl; }
    public void setCodeServerInternalUrl(String codeServerInternalUrl) { this.codeServerInternalUrl = codeServerInternalUrl; }
    public boolean isCodeServerPasswordConfigured() { return codeServerPasswordConfigured; }
    public void setCodeServerPasswordConfigured(boolean codeServerPasswordConfigured) { this.codeServerPasswordConfigured = codeServerPasswordConfigured; }
    public String getCodeServerFolderPrefix() { return codeServerFolderPrefix; }
    public void setCodeServerFolderPrefix(String codeServerFolderPrefix) { this.codeServerFolderPrefix = codeServerFolderPrefix; }

    @Override
    public String toString() {
        return "Worker{workerId='" + workerId + "', name='" + name + "', status='" + status + "'}";
    }
}
