package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;
import java.util.List;

public class ApproveUpstreamBootstrapRequestForm {
    private List<String> authorizedTenantIds;
    private String authorizedClientAppNamespace;
    private List<String> scopes;
    private Long claimTtlMinutes;
    private LocalDateTime credentialExpiresAt;

    public List<String> getAuthorizedTenantIds() { return authorizedTenantIds; }
    public void setAuthorizedTenantIds(List<String> authorizedTenantIds) { this.authorizedTenantIds = authorizedTenantIds; }
    public String getAuthorizedClientAppNamespace() { return authorizedClientAppNamespace; }
    public void setAuthorizedClientAppNamespace(String authorizedClientAppNamespace) { this.authorizedClientAppNamespace = authorizedClientAppNamespace; }
    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
    public Long getClaimTtlMinutes() { return claimTtlMinutes; }
    public void setClaimTtlMinutes(Long claimTtlMinutes) { this.claimTtlMinutes = claimTtlMinutes; }
    public LocalDateTime getCredentialExpiresAt() { return credentialExpiresAt; }
    public void setCredentialExpiresAt(LocalDateTime credentialExpiresAt) { this.credentialExpiresAt = credentialExpiresAt; }
}
