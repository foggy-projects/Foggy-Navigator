package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeTaskAuditDTO {
    private Instant observedAt;
    private Map<String, Object> taskFacts;
    private Map<String, Object> auditSideEffects;
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
    private Integer requestedToolCount;
    private Integer effectiveToolCount;
    private String toolScopeKind;
    private String toolScopeSource;
    private Integer requestedFunctionCount;
    private Integer effectiveFunctionCount;
    private String functionScopeSource;
    private Boolean taskTokenFunctionScopeEmpty;
    private Boolean runtimeDispatched;
    private Boolean taskModelDispatched;
    private Boolean taskBusinessFunctionDispatched;
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
    public Map<String, Object> getTaskFacts() { return taskFacts; }
    public void setTaskFacts(Map<String, Object> taskFacts) { this.taskFacts = taskFacts; }
    public Map<String, Object> getAuditSideEffects() { return auditSideEffects; }
    public void setAuditSideEffects(Map<String, Object> auditSideEffects) { this.auditSideEffects = auditSideEffects; }
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
    public Integer getRequestedToolCount() { return requestedToolCount; }
    public void setRequestedToolCount(Integer requestedToolCount) { this.requestedToolCount = requestedToolCount; }
    public Integer getEffectiveToolCount() { return effectiveToolCount; }
    public void setEffectiveToolCount(Integer effectiveToolCount) { this.effectiveToolCount = effectiveToolCount; }
    public String getToolScopeKind() { return toolScopeKind; }
    public void setToolScopeKind(String toolScopeKind) { this.toolScopeKind = toolScopeKind; }
    public String getToolScopeSource() { return toolScopeSource; }
    public void setToolScopeSource(String toolScopeSource) { this.toolScopeSource = toolScopeSource; }
    public Integer getRequestedFunctionCount() { return requestedFunctionCount; }
    public void setRequestedFunctionCount(Integer requestedFunctionCount) { this.requestedFunctionCount = requestedFunctionCount; }
    public Integer getEffectiveFunctionCount() { return effectiveFunctionCount; }
    public void setEffectiveFunctionCount(Integer effectiveFunctionCount) { this.effectiveFunctionCount = effectiveFunctionCount; }
    public String getFunctionScopeSource() { return functionScopeSource; }
    public void setFunctionScopeSource(String functionScopeSource) { this.functionScopeSource = functionScopeSource; }
    public Boolean getTaskTokenFunctionScopeEmpty() { return taskTokenFunctionScopeEmpty; }
    public void setTaskTokenFunctionScopeEmpty(Boolean taskTokenFunctionScopeEmpty) { this.taskTokenFunctionScopeEmpty = taskTokenFunctionScopeEmpty; }
    public Boolean getRuntimeDispatched() { return runtimeDispatched; }
    public void setRuntimeDispatched(Boolean runtimeDispatched) { this.runtimeDispatched = runtimeDispatched; }
    public Boolean getTaskModelDispatched() { return taskModelDispatched; }
    public void setTaskModelDispatched(Boolean taskModelDispatched) { this.taskModelDispatched = taskModelDispatched; }
    public Boolean getTaskBusinessFunctionDispatched() { return taskBusinessFunctionDispatched; }
    public void setTaskBusinessFunctionDispatched(Boolean taskBusinessFunctionDispatched) { this.taskBusinessFunctionDispatched = taskBusinessFunctionDispatched; }
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
