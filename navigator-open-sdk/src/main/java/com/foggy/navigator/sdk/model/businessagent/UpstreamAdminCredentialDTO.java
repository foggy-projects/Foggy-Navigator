package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;
import java.util.List;

public class UpstreamAdminCredentialDTO {
    private String credentialId;
    private String principalId;
    private String credentialKeyPrefix;
    private String credentialKeySuffix;
    private String upstreamSystemId;
    private List<String> authorizedTenantIds;
    private String authorizedClientAppNamespace;
    private List<String> scopes;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime lastUsedAt;
    private String sourceRequestId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }
    public String getPrincipalId() { return principalId; }
    public void setPrincipalId(String principalId) { this.principalId = principalId; }
    public String getCredentialKeyPrefix() { return credentialKeyPrefix; }
    public void setCredentialKeyPrefix(String credentialKeyPrefix) { this.credentialKeyPrefix = credentialKeyPrefix; }
    public String getCredentialKeySuffix() { return credentialKeySuffix; }
    public void setCredentialKeySuffix(String credentialKeySuffix) { this.credentialKeySuffix = credentialKeySuffix; }
    public String getUpstreamSystemId() { return upstreamSystemId; }
    public void setUpstreamSystemId(String upstreamSystemId) { this.upstreamSystemId = upstreamSystemId; }
    public List<String> getAuthorizedTenantIds() { return authorizedTenantIds; }
    public void setAuthorizedTenantIds(List<String> authorizedTenantIds) { this.authorizedTenantIds = authorizedTenantIds; }
    public String getAuthorizedClientAppNamespace() { return authorizedClientAppNamespace; }
    public void setAuthorizedClientAppNamespace(String authorizedClientAppNamespace) { this.authorizedClientAppNamespace = authorizedClientAppNamespace; }
    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public String getSourceRequestId() { return sourceRequestId; }
    public void setSourceRequestId(String sourceRequestId) { this.sourceRequestId = sourceRequestId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
