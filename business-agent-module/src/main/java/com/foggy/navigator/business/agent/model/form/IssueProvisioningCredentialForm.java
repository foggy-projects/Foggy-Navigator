package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IssueProvisioningCredentialForm {
    private String targetTenantId;
    private Integer maxUses;
    private LocalDateTime expiresAt;
    private String ownerUserId;
    private String capabilityDomain;
    private String auditTag;
}
