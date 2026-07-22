package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UpstreamAdminClientAppScopeDTO {
    private String credentialLane;
    private String principalType;
    private String upstreamSystemId;
    private String tenantId;
    private String clientAppId;
    private String clientAppNamespace;
    private String targetOwnerType;
    private String targetOwnerId;
    private List<String> authorizationChecks;

    public String getCredentialLane() { return credentialLane; }
    public void setCredentialLane(String credentialLane) { this.credentialLane = credentialLane; }
    public String getPrincipalType() { return principalType; }
    public void setPrincipalType(String principalType) { this.principalType = principalType; }
    public String getUpstreamSystemId() { return upstreamSystemId; }
    public void setUpstreamSystemId(String upstreamSystemId) { this.upstreamSystemId = upstreamSystemId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getClientAppNamespace() { return clientAppNamespace; }
    public void setClientAppNamespace(String clientAppNamespace) { this.clientAppNamespace = clientAppNamespace; }
    public String getTargetOwnerType() { return targetOwnerType; }
    public void setTargetOwnerType(String targetOwnerType) { this.targetOwnerType = targetOwnerType; }
    public String getTargetOwnerId() { return targetOwnerId; }
    public void setTargetOwnerId(String targetOwnerId) { this.targetOwnerId = targetOwnerId; }
    public List<String> getAuthorizationChecks() { return authorizationChecks; }
    public void setAuthorizationChecks(List<String> authorizationChecks) { this.authorizationChecks = authorizationChecks; }
}
