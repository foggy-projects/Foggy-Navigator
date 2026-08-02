package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Typed result of the dedicated terminal-cleanup repair endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeTaskTerminalCleanupRepairDTO {
    private String clientRequestId;
    private String operation = "task-terminal-cleanup-repair";
    private String taskId;
    private Boolean dryRun;
    private RuntimeTaskTerminalCleanupRepairOutcome outcome =
            RuntimeTaskTerminalCleanupRepairOutcome.UNKNOWN;
    private String reasonCode = "UNKNOWN";
    private String currentTaskStatus = "UNKNOWN";
    private Boolean canonicalTerminal;
    private Boolean terminalTombstonePresent;
    private Boolean lifecycleCleanupComplete;
    private String taskTokenStatus = "UNKNOWN";
    private Boolean activeTaskRegistrationPresent;
    private String selectedPhysicalWorkerId;
    private Boolean repairAllowed;
    private Boolean repairAccepted;
    private Boolean idempotentReplay;
    private Boolean requestReceiptPersisted;
    private RuntimeTaskFactsDTO taskFacts;
    private RuntimeAuditSideEffectsDTO auditSideEffects;
    private Boolean workerCommandDispatched;
    private Boolean runtimeDispatched;
    private Boolean modelDispatched;
    private Boolean businessFunctionDispatched;
    private Boolean terminationTriggered;
    private Boolean retryTriggered;
    private Boolean recoveryTriggered;
    private Boolean newTaskCreated;
    private Boolean newContextCreated;
    private Boolean newSessionCreated;
    private Boolean accessTokenIssued;
    private Boolean runtimeTokenIssued;
    private Boolean taskTokenIssued;
    private Boolean provisioningResourceChanged;

    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String value) { clientRequestId = value; }
    public String getOperation() { return normalized(operation); }
    public void setOperation(String value) { operation = normalized(value); }
    public String getTaskId() { return taskId; }
    public void setTaskId(String value) { taskId = value; }
    public Boolean getDryRun() { return dryRun; }
    public void setDryRun(Boolean value) { dryRun = value; }
    public RuntimeTaskTerminalCleanupRepairOutcome getOutcome() {
        return outcome == null ? RuntimeTaskTerminalCleanupRepairOutcome.UNKNOWN : outcome;
    }
    public void setOutcome(RuntimeTaskTerminalCleanupRepairOutcome value) {
        outcome = value == null ? RuntimeTaskTerminalCleanupRepairOutcome.UNKNOWN : value;
    }
    public String getReasonCode() { return normalized(reasonCode); }
    public void setReasonCode(String value) { reasonCode = normalized(value); }
    public String getCurrentTaskStatus() { return normalized(currentTaskStatus); }
    public void setCurrentTaskStatus(String value) { currentTaskStatus = normalized(value); }
    public Boolean getCanonicalTerminal() { return canonicalTerminal; }
    public void setCanonicalTerminal(Boolean value) { canonicalTerminal = value; }
    public Boolean getTerminalTombstonePresent() { return terminalTombstonePresent; }
    public void setTerminalTombstonePresent(Boolean value) { terminalTombstonePresent = value; }
    public Boolean getLifecycleCleanupComplete() { return lifecycleCleanupComplete; }
    public void setLifecycleCleanupComplete(Boolean value) { lifecycleCleanupComplete = value; }
    public String getTaskTokenStatus() { return normalized(taskTokenStatus); }
    public void setTaskTokenStatus(String value) { taskTokenStatus = normalized(value); }
    public Boolean getActiveTaskRegistrationPresent() { return activeTaskRegistrationPresent; }
    public void setActiveTaskRegistrationPresent(Boolean value) { activeTaskRegistrationPresent = value; }
    public String getSelectedPhysicalWorkerId() { return selectedPhysicalWorkerId; }
    public void setSelectedPhysicalWorkerId(String value) { selectedPhysicalWorkerId = value; }
    public Boolean getRepairAllowed() { return repairAllowed; }
    public void setRepairAllowed(Boolean value) { repairAllowed = value; }
    public Boolean getRepairAccepted() { return repairAccepted; }
    public void setRepairAccepted(Boolean value) { repairAccepted = value; }
    public Boolean getIdempotentReplay() { return idempotentReplay; }
    public void setIdempotentReplay(Boolean value) { idempotentReplay = value; }
    public Boolean getRequestReceiptPersisted() { return requestReceiptPersisted; }
    public void setRequestReceiptPersisted(Boolean value) { requestReceiptPersisted = value; }
    public RuntimeTaskFactsDTO getTaskFacts() { return taskFacts; }
    public void setTaskFacts(RuntimeTaskFactsDTO value) { taskFacts = value; }
    public RuntimeAuditSideEffectsDTO getAuditSideEffects() { return auditSideEffects; }
    public void setAuditSideEffects(RuntimeAuditSideEffectsDTO value) { auditSideEffects = value; }
    public Boolean getWorkerCommandDispatched() { return workerCommandDispatched; }
    public void setWorkerCommandDispatched(Boolean value) { workerCommandDispatched = value; }
    public Boolean getRuntimeDispatched() { return runtimeDispatched; }
    public void setRuntimeDispatched(Boolean value) { runtimeDispatched = value; }
    public Boolean getModelDispatched() { return modelDispatched; }
    public void setModelDispatched(Boolean value) { modelDispatched = value; }
    public Boolean getBusinessFunctionDispatched() { return businessFunctionDispatched; }
    public void setBusinessFunctionDispatched(Boolean value) { businessFunctionDispatched = value; }
    public Boolean getTerminationTriggered() { return terminationTriggered; }
    public void setTerminationTriggered(Boolean value) { terminationTriggered = value; }
    public Boolean getRetryTriggered() { return retryTriggered; }
    public void setRetryTriggered(Boolean value) { retryTriggered = value; }
    public Boolean getRecoveryTriggered() { return recoveryTriggered; }
    public void setRecoveryTriggered(Boolean value) { recoveryTriggered = value; }
    public Boolean getNewTaskCreated() { return newTaskCreated; }
    public void setNewTaskCreated(Boolean value) { newTaskCreated = value; }
    public Boolean getNewContextCreated() { return newContextCreated; }
    public void setNewContextCreated(Boolean value) { newContextCreated = value; }
    public Boolean getNewSessionCreated() { return newSessionCreated; }
    public void setNewSessionCreated(Boolean value) { newSessionCreated = value; }
    public Boolean getAccessTokenIssued() { return accessTokenIssued; }
    public void setAccessTokenIssued(Boolean value) { accessTokenIssued = value; }
    public Boolean getRuntimeTokenIssued() { return runtimeTokenIssued; }
    public void setRuntimeTokenIssued(Boolean value) { runtimeTokenIssued = value; }
    public Boolean getTaskTokenIssued() { return taskTokenIssued; }
    public void setTaskTokenIssued(Boolean value) { taskTokenIssued = value; }
    public Boolean getProvisioningResourceChanged() { return provisioningResourceChanged; }
    public void setProvisioningResourceChanged(Boolean value) { provisioningResourceChanged = value; }

    private String normalized(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
