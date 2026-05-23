package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class EnsureUpstreamTenantClientAppForm {
    private String sourceSystem;
    private String sourceTenantId;
    private String clientAppName;
    private String capabilityDomain;
    private String tenantName;
    private String agentRole;
    private String agentBundleCode;
    private String modelProfileCode;
    private String modelConfigId;
    private String skillId;
    private String workerPoolId;
    private Boolean rotateCredentials;
}
