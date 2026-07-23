package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeRequestAuditDTO {
    private String clientRequestId;
    private String operation;
    private Instant receivedAt;
    private Instant completedAt;
    private Boolean terminal;
    private String result;
    private String sanitizedErrorCode;
    private String safeErrorSummary;
    private Boolean httpRequestReceived;
    private Boolean runtimeTokenRequestReceived;
    private Boolean runtimeTokenIssued;
    private Boolean safeSmokeRequestReceived;
    private Boolean syntheticEvidenceCreated;
    private String taskId;
    private String status;
    private Integer effectiveToolCount;
    private String toolScopeKind;
    private String toolScopeSource;
    private Integer effectiveFunctionCount;
    private String functionScopeSource;
    private Boolean taskTokenFunctionScopeEmpty;
    private String taskTokenStatus;
    private Boolean runtimeDispatched;
    private List<RuntimeRequestAuditStageDTO> stages;

    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Boolean getTerminal() { return terminal; }
    public void setTerminal(Boolean terminal) { this.terminal = terminal; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getSanitizedErrorCode() { return sanitizedErrorCode; }
    public void setSanitizedErrorCode(String sanitizedErrorCode) { this.sanitizedErrorCode = sanitizedErrorCode; }
    public String getSafeErrorSummary() { return safeErrorSummary; }
    public void setSafeErrorSummary(String safeErrorSummary) { this.safeErrorSummary = safeErrorSummary; }
    public Boolean getHttpRequestReceived() { return httpRequestReceived; }
    public void setHttpRequestReceived(Boolean httpRequestReceived) { this.httpRequestReceived = httpRequestReceived; }
    public Boolean getRuntimeTokenRequestReceived() { return runtimeTokenRequestReceived; }
    public void setRuntimeTokenRequestReceived(Boolean runtimeTokenRequestReceived) { this.runtimeTokenRequestReceived = runtimeTokenRequestReceived; }
    public Boolean getRuntimeTokenIssued() { return runtimeTokenIssued; }
    public void setRuntimeTokenIssued(Boolean runtimeTokenIssued) { this.runtimeTokenIssued = runtimeTokenIssued; }
    public Boolean getSafeSmokeRequestReceived() { return safeSmokeRequestReceived; }
    public void setSafeSmokeRequestReceived(Boolean safeSmokeRequestReceived) { this.safeSmokeRequestReceived = safeSmokeRequestReceived; }
    public Boolean getSyntheticEvidenceCreated() { return syntheticEvidenceCreated; }
    public void setSyntheticEvidenceCreated(Boolean syntheticEvidenceCreated) { this.syntheticEvidenceCreated = syntheticEvidenceCreated; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getEffectiveToolCount() { return effectiveToolCount; }
    public void setEffectiveToolCount(Integer effectiveToolCount) { this.effectiveToolCount = effectiveToolCount; }
    public String getToolScopeKind() { return toolScopeKind; }
    public void setToolScopeKind(String toolScopeKind) { this.toolScopeKind = toolScopeKind; }
    public String getToolScopeSource() { return toolScopeSource; }
    public void setToolScopeSource(String toolScopeSource) { this.toolScopeSource = toolScopeSource; }
    public Integer getEffectiveFunctionCount() { return effectiveFunctionCount; }
    public void setEffectiveFunctionCount(Integer effectiveFunctionCount) { this.effectiveFunctionCount = effectiveFunctionCount; }
    public String getFunctionScopeSource() { return functionScopeSource; }
    public void setFunctionScopeSource(String functionScopeSource) { this.functionScopeSource = functionScopeSource; }
    public Boolean getTaskTokenFunctionScopeEmpty() { return taskTokenFunctionScopeEmpty; }
    public void setTaskTokenFunctionScopeEmpty(Boolean taskTokenFunctionScopeEmpty) { this.taskTokenFunctionScopeEmpty = taskTokenFunctionScopeEmpty; }
    public String getTaskTokenStatus() { return taskTokenStatus; }
    public void setTaskTokenStatus(String taskTokenStatus) { this.taskTokenStatus = taskTokenStatus; }
    public Boolean getRuntimeDispatched() { return runtimeDispatched; }
    public void setRuntimeDispatched(Boolean runtimeDispatched) { this.runtimeDispatched = runtimeDispatched; }
    public List<RuntimeRequestAuditStageDTO> getStages() { return stages; }
    public void setStages(List<RuntimeRequestAuditStageDTO> stages) { this.stages = stages; }
}
