package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientAppUpstreamRouteDTO {
    private Long id;
    private String tenantId;
    private String clientAppId;
    private String upstreamRef;
    private String baseUrl;
    private String userTokenHeader;
    private String status;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getUpstreamRef() { return upstreamRef; }
    public void setUpstreamRef(String upstreamRef) { this.upstreamRef = upstreamRef; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getUserTokenHeader() { return userTokenHeader; }
    public void setUserTokenHeader(String userTokenHeader) { this.userTokenHeader = userTokenHeader; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
