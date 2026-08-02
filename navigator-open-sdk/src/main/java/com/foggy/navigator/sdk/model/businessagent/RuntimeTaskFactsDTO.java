package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;

/** Durable server facts for completion readiness and cleanup repair. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeTaskFactsDTO {
    private String taskId;
    private Boolean terminal;
    private Boolean lifecycleCanonicalTerminal;
    private Boolean terminalTombstonePresent;
    private Boolean lifecycleCleanupComplete;
    private String status = "UNKNOWN";
    private String sanitizedErrorCode;
    private String taskTokenStatus = "UNKNOWN";
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
    private Boolean modelDispatched;
    private Boolean businessFunctionDispatched;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
    private List<RuntimeTaskAuditStageDTO> stages;

    public String getTaskId() { return taskId; }
    public void setTaskId(String value) { taskId = value; }
    public Boolean getTerminal() { return terminal; }
    public void setTerminal(Boolean value) { terminal = value; }
    public Boolean getLifecycleCanonicalTerminal() { return lifecycleCanonicalTerminal; }
    public void setLifecycleCanonicalTerminal(Boolean value) { lifecycleCanonicalTerminal = value; }
    public Boolean getTerminalTombstonePresent() { return terminalTombstonePresent; }
    public void setTerminalTombstonePresent(Boolean value) { terminalTombstonePresent = value; }
    public Boolean getLifecycleCleanupComplete() { return lifecycleCleanupComplete; }
    public void setLifecycleCleanupComplete(Boolean value) { lifecycleCleanupComplete = value; }
    public String getStatus() { return normalized(status); }
    public void setStatus(String value) { status = normalized(value); }
    public String getSanitizedErrorCode() { return sanitizedErrorCode; }
    public void setSanitizedErrorCode(String value) { sanitizedErrorCode = value; }
    public String getTaskTokenStatus() { return normalized(taskTokenStatus); }
    public void setTaskTokenStatus(String value) { taskTokenStatus = normalized(value); }
    public Boolean getActiveTaskRegistrationPresent() { return activeTaskRegistrationPresent; }
    public void setActiveTaskRegistrationPresent(Boolean value) { activeTaskRegistrationPresent = value; }
    public Integer getDispatchCount() { return dispatchCount; }
    public void setDispatchCount(Integer value) { dispatchCount = value; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer value) { retryCount = value; }
    public Integer getRecoveryCount() { return recoveryCount; }
    public void setRecoveryCount(Integer value) { recoveryCount = value; }
    public String getPhysicalWorkerId() { return physicalWorkerId; }
    public void setPhysicalWorkerId(String value) { physicalWorkerId = value; }
    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String value) { modelConfigId = value; }
    public String getModelVariant() { return modelVariant; }
    public void setModelVariant(String value) { modelVariant = value; }
    public Integer getRequestedToolCount() { return requestedToolCount; }
    public void setRequestedToolCount(Integer value) { requestedToolCount = value; }
    public Integer getEffectiveToolCount() { return effectiveToolCount; }
    public void setEffectiveToolCount(Integer value) { effectiveToolCount = value; }
    public String getToolScopeKind() { return toolScopeKind; }
    public void setToolScopeKind(String value) { toolScopeKind = value; }
    public String getToolScopeSource() { return toolScopeSource; }
    public void setToolScopeSource(String value) { toolScopeSource = value; }
    public Integer getRequestedFunctionCount() { return requestedFunctionCount; }
    public void setRequestedFunctionCount(Integer value) { requestedFunctionCount = value; }
    public Integer getEffectiveFunctionCount() { return effectiveFunctionCount; }
    public void setEffectiveFunctionCount(Integer value) { effectiveFunctionCount = value; }
    public String getFunctionScopeSource() { return functionScopeSource; }
    public void setFunctionScopeSource(String value) { functionScopeSource = value; }
    public Boolean getTaskTokenFunctionScopeEmpty() { return taskTokenFunctionScopeEmpty; }
    public void setTaskTokenFunctionScopeEmpty(Boolean value) { taskTokenFunctionScopeEmpty = value; }
    public Boolean getRuntimeDispatched() { return runtimeDispatched; }
    public void setRuntimeDispatched(Boolean value) { runtimeDispatched = value; }
    public Boolean getModelDispatched() { return modelDispatched; }
    public void setModelDispatched(Boolean value) { modelDispatched = value; }
    public Boolean getBusinessFunctionDispatched() { return businessFunctionDispatched; }
    public void setBusinessFunctionDispatched(Boolean value) { businessFunctionDispatched = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime value) { completedAt = value; }
    public List<RuntimeTaskAuditStageDTO> getStages() { return stages; }
    public void setStages(List<RuntimeTaskAuditStageDTO> value) { stages = value; }

    private String normalized(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
