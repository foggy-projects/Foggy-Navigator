package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;
import java.util.List;

public class UpstreamBootstrapRequestDTO {
    private String requestId;
    private String requestCodeSuffix;
    private String upstreamSystemId;
    private String requestedTenantId;
    private Boolean multiTenant;
    private String reason;
    private String applicantLabel;
    private String status;
    private String deniedReason;
    private List<String> authorizedTenantIds;
    private String authorizedClientAppNamespace;
    private List<String> scopes;
    private LocalDateTime requestExpiresAt;
    private LocalDateTime approvedAt;
    private LocalDateTime deniedAt;
    private LocalDateTime claimExpiresAt;
    private LocalDateTime adminCredentialExpiresAt;
    private LocalDateTime consumedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRequestCodeSuffix() { return requestCodeSuffix; }
    public void setRequestCodeSuffix(String requestCodeSuffix) { this.requestCodeSuffix = requestCodeSuffix; }
    public String getUpstreamSystemId() { return upstreamSystemId; }
    public void setUpstreamSystemId(String upstreamSystemId) { this.upstreamSystemId = upstreamSystemId; }
    public String getRequestedTenantId() { return requestedTenantId; }
    public void setRequestedTenantId(String requestedTenantId) { this.requestedTenantId = requestedTenantId; }
    public Boolean getMultiTenant() { return multiTenant; }
    public void setMultiTenant(Boolean multiTenant) { this.multiTenant = multiTenant; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getApplicantLabel() { return applicantLabel; }
    public void setApplicantLabel(String applicantLabel) { this.applicantLabel = applicantLabel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDeniedReason() { return deniedReason; }
    public void setDeniedReason(String deniedReason) { this.deniedReason = deniedReason; }
    public List<String> getAuthorizedTenantIds() { return authorizedTenantIds; }
    public void setAuthorizedTenantIds(List<String> authorizedTenantIds) { this.authorizedTenantIds = authorizedTenantIds; }
    public String getAuthorizedClientAppNamespace() { return authorizedClientAppNamespace; }
    public void setAuthorizedClientAppNamespace(String authorizedClientAppNamespace) { this.authorizedClientAppNamespace = authorizedClientAppNamespace; }
    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
    public LocalDateTime getRequestExpiresAt() { return requestExpiresAt; }
    public void setRequestExpiresAt(LocalDateTime requestExpiresAt) { this.requestExpiresAt = requestExpiresAt; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }
    public LocalDateTime getDeniedAt() { return deniedAt; }
    public void setDeniedAt(LocalDateTime deniedAt) { this.deniedAt = deniedAt; }
    public LocalDateTime getClaimExpiresAt() { return claimExpiresAt; }
    public void setClaimExpiresAt(LocalDateTime claimExpiresAt) { this.claimExpiresAt = claimExpiresAt; }
    public LocalDateTime getAdminCredentialExpiresAt() { return adminCredentialExpiresAt; }
    public void setAdminCredentialExpiresAt(LocalDateTime adminCredentialExpiresAt) { this.adminCredentialExpiresAt = adminCredentialExpiresAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
