package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;
import java.util.List;

public class UpstreamAdminCredentialClaimDTO {
    private String credentialId;
    private String naviAdminApiKey;
    private String upstreamSystemId;
    private List<String> authorizedTenantIds;
    private String authorizedClientAppNamespace;
    private List<String> scopes;
    private LocalDateTime expiresAt;

    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }
    public String getNaviAdminApiKey() { return naviAdminApiKey; }
    public void setNaviAdminApiKey(String naviAdminApiKey) { this.naviAdminApiKey = naviAdminApiKey; }
    public String getUpstreamSystemId() { return upstreamSystemId; }
    public void setUpstreamSystemId(String upstreamSystemId) { this.upstreamSystemId = upstreamSystemId; }
    public List<String> getAuthorizedTenantIds() { return authorizedTenantIds; }
    public void setAuthorizedTenantIds(List<String> authorizedTenantIds) { this.authorizedTenantIds = authorizedTenantIds; }
    public String getAuthorizedClientAppNamespace() { return authorizedClientAppNamespace; }
    public void setAuthorizedClientAppNamespace(String authorizedClientAppNamespace) { this.authorizedClientAppNamespace = authorizedClientAppNamespace; }
    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
