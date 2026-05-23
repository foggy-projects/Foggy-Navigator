package com.foggy.navigator.business.agent.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpstreamBootstrapApprovalActor {
    private boolean operator;
    private boolean superAdmin;
    private boolean tenantAdmin;
    private String tenantId;
    private String userId;
    private String operatorCredentialId;
}
