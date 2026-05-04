package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class IssueProvisioningCredentialForm {
    private String targetTenantId;
    private Integer maxUses;
    private LocalDateTime expiresAt;
    private String ownerUserId;
    private String capabilityDomain;
    private String auditTag;

    public String getTargetTenantId() { return targetTenantId; }
    public void setTargetTenantId(String targetTenantId) { this.targetTenantId = targetTenantId; }
    public Integer getMaxUses() { return maxUses; }
    public void setMaxUses(Integer maxUses) { this.maxUses = maxUses; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getCapabilityDomain() { return capabilityDomain; }
    public void setCapabilityDomain(String capabilityDomain) { this.capabilityDomain = capabilityDomain; }
    public String getAuditTag() { return auditTag; }
    public void setAuditTag(String auditTag) { this.auditTag = auditTag; }
}
