package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 员工 Provisioning 结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProvisionResult {
    private String externalUserId;
    private String userId;
    private String username;
    private String password;
    private String directoryId;
    private String directoryPath;
    private String agentId;
    private boolean userCreated;
    private boolean directoryCreated;

    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDirectoryId() { return directoryId; }
    public void setDirectoryId(String directoryId) { this.directoryId = directoryId; }
    public String getDirectoryPath() { return directoryPath; }
    public void setDirectoryPath(String directoryPath) { this.directoryPath = directoryPath; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public boolean isUserCreated() { return userCreated; }
    public void setUserCreated(boolean userCreated) { this.userCreated = userCreated; }
    public boolean isDirectoryCreated() { return directoryCreated; }
    public void setDirectoryCreated(boolean directoryCreated) { this.directoryCreated = directoryCreated; }

    @Override
    public String toString() {
        return "ProvisionResult{externalUserId='" + externalUserId + "', agentId='" + agentId +
                "', userCreated=" + userCreated + ", directoryCreated=" + directoryCreated + "}";
    }
}
