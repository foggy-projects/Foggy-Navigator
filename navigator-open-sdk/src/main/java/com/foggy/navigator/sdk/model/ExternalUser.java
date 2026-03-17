package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

/**
 * 外部用户映射
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExternalUser {
    private String externalUserId;
    private String externalDisplayName;
    private String userId;
    private String username;
    private LocalDateTime createdAt;

    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }
    public String getExternalDisplayName() { return externalDisplayName; }
    public void setExternalDisplayName(String externalDisplayName) { this.externalDisplayName = externalDisplayName; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "ExternalUser{externalUserId='" + externalUserId + "', username='" + username + "'}";
    }
}
