package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UpstreamTenantClientAppProvisioningDTO {
    private String navigatorTenantId;
    private String targetNavigatorTenantId;
    private String clientAppId;
    private String clientAppName;
    private String capabilityDomain;
    private String clientAppCapabilityDomain;
    private String upstreamSystemId;
    private String sourceTenantId;
    private String upstreamRef;
    private String upstreamNamespace;
    private String clientAppKey;
    private String clientAppSecret;
    private String controlApiKey;
    private String agentCode;
    private String rootAgentId;
    private String modelConfigId;
    private String skillId;
    private String workerPoolId;
    private String workerBackend;
    private String physicalWorkerId;
    private String directoryId;
    private String bizWorkerBaseUrl;
    private String bindingVersion;
    private String status;
    private String errorCode;
    private String message;
    private String remediationHint;
    private Boolean credentialsReplayable;
    private Boolean created;
    private Boolean rotated;
    private Boolean activationReady;
    private List<String> blockers;
    private List<String> missingFields;
    private List<String> requiredScopes;
    private List<String> actualScopes;
    private List<String> authorizedTenantIds;

    public String getNavigatorTenantId() { return navigatorTenantId; }
    public void setNavigatorTenantId(String navigatorTenantId) { this.navigatorTenantId = navigatorTenantId; }
    public String getTargetNavigatorTenantId() { return targetNavigatorTenantId; }
    public void setTargetNavigatorTenantId(String targetNavigatorTenantId) { this.targetNavigatorTenantId = targetNavigatorTenantId; }
    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getClientAppName() { return clientAppName; }
    public void setClientAppName(String clientAppName) { this.clientAppName = clientAppName; }
    public String getCapabilityDomain() { return capabilityDomain; }
    public void setCapabilityDomain(String capabilityDomain) { this.capabilityDomain = capabilityDomain; }
    public String getClientAppCapabilityDomain() { return clientAppCapabilityDomain; }
    public void setClientAppCapabilityDomain(String clientAppCapabilityDomain) { this.clientAppCapabilityDomain = clientAppCapabilityDomain; }
    public String getUpstreamSystemId() { return upstreamSystemId; }
    public void setUpstreamSystemId(String upstreamSystemId) { this.upstreamSystemId = upstreamSystemId; }
    public String getSourceTenantId() { return sourceTenantId; }
    public void setSourceTenantId(String sourceTenantId) { this.sourceTenantId = sourceTenantId; }
    public String getUpstreamRef() { return upstreamRef; }
    public void setUpstreamRef(String upstreamRef) { this.upstreamRef = upstreamRef; }
    public String getUpstreamNamespace() { return upstreamNamespace; }
    public void setUpstreamNamespace(String upstreamNamespace) { this.upstreamNamespace = upstreamNamespace; }
    public String getClientAppKey() { return clientAppKey; }
    public void setClientAppKey(String clientAppKey) { this.clientAppKey = clientAppKey; }
    public String getClientAppSecret() { return clientAppSecret; }
    public void setClientAppSecret(String clientAppSecret) { this.clientAppSecret = clientAppSecret; }
    public String getControlApiKey() { return controlApiKey; }
    public void setControlApiKey(String controlApiKey) { this.controlApiKey = controlApiKey; }
    public String getAgentCode() { return agentCode; }
    public void setAgentCode(String agentCode) { this.agentCode = agentCode; }
    public String getRootAgentId() { return rootAgentId; }
    public void setRootAgentId(String rootAgentId) { this.rootAgentId = rootAgentId; }
    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getWorkerPoolId() { return workerPoolId; }
    public void setWorkerPoolId(String workerPoolId) { this.workerPoolId = workerPoolId; }
    public String getWorkerBackend() { return workerBackend; }
    public void setWorkerBackend(String workerBackend) { this.workerBackend = workerBackend; }
    public String getPhysicalWorkerId() { return physicalWorkerId; }
    public void setPhysicalWorkerId(String physicalWorkerId) { this.physicalWorkerId = physicalWorkerId; }
    public String getDirectoryId() { return directoryId; }
    public void setDirectoryId(String directoryId) { this.directoryId = directoryId; }
    public String getBizWorkerBaseUrl() { return bizWorkerBaseUrl; }
    public void setBizWorkerBaseUrl(String bizWorkerBaseUrl) { this.bizWorkerBaseUrl = bizWorkerBaseUrl; }
    public String getBindingVersion() { return bindingVersion; }
    public void setBindingVersion(String bindingVersion) { this.bindingVersion = bindingVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getRemediationHint() { return remediationHint; }
    public void setRemediationHint(String remediationHint) { this.remediationHint = remediationHint; }
    public Boolean getCredentialsReplayable() { return credentialsReplayable; }
    public void setCredentialsReplayable(Boolean credentialsReplayable) { this.credentialsReplayable = credentialsReplayable; }
    public Boolean getCreated() { return created; }
    public void setCreated(Boolean created) { this.created = created; }
    public Boolean getRotated() { return rotated; }
    public void setRotated(Boolean rotated) { this.rotated = rotated; }
    public Boolean getActivationReady() { return activationReady; }
    public void setActivationReady(Boolean activationReady) { this.activationReady = activationReady; }
    public List<String> getBlockers() { return blockers; }
    public void setBlockers(List<String> blockers) { this.blockers = blockers; }
    public List<String> getMissingFields() { return missingFields; }
    public void setMissingFields(List<String> missingFields) { this.missingFields = missingFields; }
    public List<String> getRequiredScopes() { return requiredScopes; }
    public void setRequiredScopes(List<String> requiredScopes) { this.requiredScopes = requiredScopes; }
    public List<String> getActualScopes() { return actualScopes; }
    public void setActualScopes(List<String> actualScopes) { this.actualScopes = actualScopes; }
    public List<String> getAuthorizedTenantIds() { return authorizedTenantIds; }
    public void setAuthorizedTenantIds(List<String> authorizedTenantIds) { this.authorizedTenantIds = authorizedTenantIds; }
}
