package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 系统注册结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegisterResult {
    private String tenantId;
    private String userId;
    private String username;
    private String apiKey;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    @Override
    public String toString() {
        return "RegisterResult{tenantId='" + tenantId + "', username='" + username + "'}";
    }
}
