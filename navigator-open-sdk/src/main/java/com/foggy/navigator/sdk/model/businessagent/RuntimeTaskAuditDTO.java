package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeTaskAuditDTO {
    private Instant observedAt;
    private String taskId;
    private Boolean terminal;
    private String status;
    private String sanitizedErrorCode;
    private String taskTokenStatus;
    private Boolean activeTaskRegistrationPresent;
    private Integer dispatchCount;
    private Integer retryCount;
    private Integer recoveryCount;
    private String physicalWorkerId;
    private String modelConfigId;
    private String modelVariant;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private List<RuntimeTaskAuditStageDTO> terminalStages;
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
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Boolean getTerminal() { return terminal; }
    public void setTerminal(Boolean terminal) { this.terminal = terminal; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSanitizedErrorCode() { return sanitizedErrorCode; }
    public void setSanitizedErrorCode(String sanitizedErrorCode) { this.sanitizedErrorCode = sanitizedErrorCode; }
    public String getTaskTokenStatus() { return taskTokenStatus; }
    public void setTaskTokenStatus(String taskTokenStatus) { this.taskTokenStatus = taskTokenStatus; }
    public Boolean getActiveTaskRegistrationPresent() { return activeTaskRegistrationPresent; }
    public void setActiveTaskRegistrationPresent(Boolean activeTaskRegistrationPresent) { this.activeTaskRegistrationPresent = activeTaskRegistrationPresent; }
    public Integer getDispatchCount() { return dispatchCount; }
    public void setDispatchCount(Integer dispatchCount) { this.dispatchCount = dispatchCount; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Integer getRecoveryCount() { return recoveryCount; }
    public void setRecoveryCount(Integer recoveryCount) { this.recoveryCount = recoveryCount; }
    public String getPhysicalWorkerId() { return physicalWorkerId; }
    public void setPhysicalWorkerId(String physicalWorkerId) { this.physicalWorkerId = physicalWorkerId; }
    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; }
    public String getModelVariant() { return modelVariant; }
    public void setModelVariant(String modelVariant) { this.modelVariant = modelVariant; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public List<RuntimeTaskAuditStageDTO> getTerminalStages() { return terminalStages; }
    public void setTerminalStages(List<RuntimeTaskAuditStageDTO> terminalStages) { this.terminalStages = terminalStages; }
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
