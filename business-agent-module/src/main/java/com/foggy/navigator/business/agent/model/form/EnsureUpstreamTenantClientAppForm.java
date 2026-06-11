package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class EnsureUpstreamTenantClientAppForm {
    private String sourceSystem;
    private String sourceTenantId;
    private String clientAppName;
    private String capabilityDomain;
    private String tenantName;
    private String upstreamRef;
    private String agentRole;
    private String agentBundleCode;
    private String agentCode;
    private String modelProfileCode;
    private String modelConfigId;
    private String skillId;
    private String workerPoolId;
    private String workerBackend;
    private String physicalWorkerId;
    private String directoryId;
    private String bizWorkerBaseUrl;
    private Boolean rotateCredentials;
}
