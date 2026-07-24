package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeBindingAuditDTO {
    private Instant observedAt;
    private String tenant;
    private String upstreamUserId;
    private String agentCode;
    private Boolean agentEnabled;
    private String modelConfigId;
    private String modelVariant;
    private String modelBackend;
    private String directoryId;
    private Boolean directoryEnabled;
    private String workerHost;
    private String physicalWorkerId;
    private String physicalWorkerStatus;
    private Integer directoryRolePort;
    private Integer codexRolePort;
    private String codexRoleSource;
    private Boolean codexRoleSamePhysicalWorker;
    private Long activeTaskCount;
    private Boolean auditAccessTokenIssued;
    private Boolean auditRuntimeTokenIssued;
    private Boolean auditTaskTokenIssued;
    private Boolean taskCreated;
    private Boolean contextCreated;
    private Boolean sessionCreated;
    private Boolean modelDispatched;
    private Boolean businessFunctionDispatched;
    private Boolean recoveryTriggered;
    private Boolean provisioningResourceChanged;

    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }
    public String getTenant() { return tenant; }
    public void setTenant(String tenant) { this.tenant = tenant; }
    public String getUpstreamUserId() { return upstreamUserId; }
    public void setUpstreamUserId(String upstreamUserId) { this.upstreamUserId = upstreamUserId; }
    public String getAgentCode() { return agentCode; }
    public void setAgentCode(String agentCode) { this.agentCode = agentCode; }
    public Boolean getAgentEnabled() { return agentEnabled; }
    public void setAgentEnabled(Boolean agentEnabled) { this.agentEnabled = agentEnabled; }
    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; }
    public String getModelVariant() { return modelVariant; }
    public void setModelVariant(String modelVariant) { this.modelVariant = modelVariant; }
    public String getModelBackend() { return modelBackend; }
    public void setModelBackend(String modelBackend) { this.modelBackend = modelBackend; }
    public String getDirectoryId() { return directoryId; }
    public void setDirectoryId(String directoryId) { this.directoryId = directoryId; }
    public Boolean getDirectoryEnabled() { return directoryEnabled; }
    public void setDirectoryEnabled(Boolean directoryEnabled) { this.directoryEnabled = directoryEnabled; }
    public String getWorkerHost() { return workerHost; }
    public void setWorkerHost(String workerHost) { this.workerHost = workerHost; }
    public String getPhysicalWorkerId() { return physicalWorkerId; }
    public void setPhysicalWorkerId(String physicalWorkerId) { this.physicalWorkerId = physicalWorkerId; }
    public String getPhysicalWorkerStatus() { return physicalWorkerStatus; }
    public void setPhysicalWorkerStatus(String physicalWorkerStatus) { this.physicalWorkerStatus = physicalWorkerStatus; }
    public Integer getDirectoryRolePort() { return directoryRolePort; }
    public void setDirectoryRolePort(Integer directoryRolePort) { this.directoryRolePort = directoryRolePort; }
    public Integer getCodexRolePort() { return codexRolePort; }
    public void setCodexRolePort(Integer codexRolePort) { this.codexRolePort = codexRolePort; }
    public String getCodexRoleSource() { return codexRoleSource; }
    public void setCodexRoleSource(String codexRoleSource) { this.codexRoleSource = codexRoleSource; }
    public Boolean getCodexRoleSamePhysicalWorker() { return codexRoleSamePhysicalWorker; }
    public void setCodexRoleSamePhysicalWorker(Boolean codexRoleSamePhysicalWorker) { this.codexRoleSamePhysicalWorker = codexRoleSamePhysicalWorker; }
    public Long getActiveTaskCount() { return activeTaskCount; }
    public void setActiveTaskCount(Long activeTaskCount) { this.activeTaskCount = activeTaskCount; }
    public Boolean getAuditAccessTokenIssued() { return auditAccessTokenIssued; }
    public void setAuditAccessTokenIssued(Boolean auditAccessTokenIssued) { this.auditAccessTokenIssued = auditAccessTokenIssued; }
    public Boolean getAuditRuntimeTokenIssued() { return auditRuntimeTokenIssued; }
    public void setAuditRuntimeTokenIssued(Boolean auditRuntimeTokenIssued) { this.auditRuntimeTokenIssued = auditRuntimeTokenIssued; }
    public Boolean getAuditTaskTokenIssued() { return auditTaskTokenIssued; }
    public void setAuditTaskTokenIssued(Boolean auditTaskTokenIssued) { this.auditTaskTokenIssued = auditTaskTokenIssued; }
    public Boolean getTaskCreated() { return taskCreated; }
    public void setTaskCreated(Boolean taskCreated) { this.taskCreated = taskCreated; }
    public Boolean getContextCreated() { return contextCreated; }
    public void setContextCreated(Boolean contextCreated) { this.contextCreated = contextCreated; }
    public Boolean getSessionCreated() { return sessionCreated; }
    public void setSessionCreated(Boolean sessionCreated) { this.sessionCreated = sessionCreated; }
    public Boolean getModelDispatched() { return modelDispatched; }
    public void setModelDispatched(Boolean modelDispatched) { this.modelDispatched = modelDispatched; }
    public Boolean getBusinessFunctionDispatched() { return businessFunctionDispatched; }
    public void setBusinessFunctionDispatched(Boolean businessFunctionDispatched) { this.businessFunctionDispatched = businessFunctionDispatched; }
    public Boolean getRecoveryTriggered() { return recoveryTriggered; }
    public void setRecoveryTriggered(Boolean recoveryTriggered) { this.recoveryTriggered = recoveryTriggered; }
    public Boolean getProvisioningResourceChanged() { return provisioningResourceChanged; }
    public void setProvisioningResourceChanged(Boolean provisioningResourceChanged) { this.provisioningResourceChanged = provisioningResourceChanged; }
}
