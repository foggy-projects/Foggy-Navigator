package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
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

    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
    public String getSourceTenantId() { return sourceTenantId; }
    public void setSourceTenantId(String sourceTenantId) { this.sourceTenantId = sourceTenantId; }
    public String getClientAppName() { return clientAppName; }
    public void setClientAppName(String clientAppName) { this.clientAppName = clientAppName; }
    public String getCapabilityDomain() { return capabilityDomain; }
    public void setCapabilityDomain(String capabilityDomain) { this.capabilityDomain = capabilityDomain; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getAgentRole() { return agentRole; }
    public void setAgentRole(String agentRole) { this.agentRole = agentRole; }
    public String getAgentBundleCode() { return agentBundleCode; }
    public void setAgentBundleCode(String agentBundleCode) { this.agentBundleCode = agentBundleCode; }
    public String getModelProfileCode() { return modelProfileCode; }
    public void setModelProfileCode(String modelProfileCode) { this.modelProfileCode = modelProfileCode; }
    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getWorkerPoolId() { return workerPoolId; }
    public void setWorkerPoolId(String workerPoolId) { this.workerPoolId = workerPoolId; }
    public Boolean getRotateCredentials() { return rotateCredentials; }
    public void setRotateCredentials(Boolean rotateCredentials) { this.rotateCredentials = rotateCredentials; }
}
