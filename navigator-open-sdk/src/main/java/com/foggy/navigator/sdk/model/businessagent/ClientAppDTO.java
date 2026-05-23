package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientAppDTO {
    private String clientAppId;
    private String tenantId;
    private String name;
    private String description;
    private String ownerUserId;
    private String capabilityDomain;
    private String upstreamSystemId;
    private String upstreamClientAppNamespace;
    private String upstreamRef;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getCapabilityDomain() { return capabilityDomain; }
    public void setCapabilityDomain(String capabilityDomain) { this.capabilityDomain = capabilityDomain; }
    public String getUpstreamSystemId() { return upstreamSystemId; }
    public void setUpstreamSystemId(String upstreamSystemId) { this.upstreamSystemId = upstreamSystemId; }
    public String getUpstreamClientAppNamespace() { return upstreamClientAppNamespace; }
    public void setUpstreamClientAppNamespace(String upstreamClientAppNamespace) { this.upstreamClientAppNamespace = upstreamClientAppNamespace; }
    public String getUpstreamRef() { return upstreamRef; }
    public void setUpstreamRef(String upstreamRef) { this.upstreamRef = upstreamRef; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
