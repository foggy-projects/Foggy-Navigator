package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UpstreamTenantClientAppProvisioningDTO {
    private String navigatorTenantId;
    private String clientAppId;
    private String clientAppName;
    private String capabilityDomain;
    private String clientAppKey;
    private String clientAppSecret;
    private String controlApiKey;
    private String rootAgentId;
    private String modelConfigId;
    private String skillId;
    private String workerPoolId;
    private String bindingVersion;
    private String status;
    private String errorCode;
    private String message;
    private Boolean credentialsReplayable;
    private Boolean created;
    private Boolean rotated;
    private List<String> blockers;

    public String getNavigatorTenantId() { return navigatorTenantId; }
    public void setNavigatorTenantId(String navigatorTenantId) { this.navigatorTenantId = navigatorTenantId; }
    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getClientAppName() { return clientAppName; }
    public void setClientAppName(String clientAppName) { this.clientAppName = clientAppName; }
    public String getCapabilityDomain() { return capabilityDomain; }
    public void setCapabilityDomain(String capabilityDomain) { this.capabilityDomain = capabilityDomain; }
    public String getClientAppKey() { return clientAppKey; }
    public void setClientAppKey(String clientAppKey) { this.clientAppKey = clientAppKey; }
    public String getClientAppSecret() { return clientAppSecret; }
    public void setClientAppSecret(String clientAppSecret) { this.clientAppSecret = clientAppSecret; }
    public String getControlApiKey() { return controlApiKey; }
    public void setControlApiKey(String controlApiKey) { this.controlApiKey = controlApiKey; }
    public String getRootAgentId() { return rootAgentId; }
    public void setRootAgentId(String rootAgentId) { this.rootAgentId = rootAgentId; }
    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getWorkerPoolId() { return workerPoolId; }
    public void setWorkerPoolId(String workerPoolId) { this.workerPoolId = workerPoolId; }
    public String getBindingVersion() { return bindingVersion; }
    public void setBindingVersion(String bindingVersion) { this.bindingVersion = bindingVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Boolean getCredentialsReplayable() { return credentialsReplayable; }
    public void setCredentialsReplayable(Boolean credentialsReplayable) { this.credentialsReplayable = credentialsReplayable; }
    public Boolean getCreated() { return created; }
    public void setCreated(Boolean created) { this.created = created; }
    public Boolean getRotated() { return rotated; }
    public void setRotated(Boolean rotated) { this.rotated = rotated; }
    public List<String> getBlockers() { return blockers; }
    public void setBlockers(List<String> blockers) { this.blockers = blockers; }
}
