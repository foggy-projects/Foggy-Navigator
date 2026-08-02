package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Side effects caused by the read-only audit/readiness request itself. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeAuditSideEffectsDTO {
    private Boolean accessTokenIssued;
    private Boolean runtimeTokenIssued;
    private Boolean taskTokenIssued;
    private Boolean taskCreated;
    private Boolean contextCreated;
    private Boolean sessionCreated;
    private Boolean workerCommandDispatched;
    private Boolean modelDispatched;
    private Boolean businessFunctionDispatched;
    private Boolean retryTriggered;
    private Boolean recoveryTriggered;
    private Boolean terminationTriggered;
    private Boolean reconciliationTriggered;
    private Boolean provisioningResourceChanged;

    public Boolean getAccessTokenIssued() { return accessTokenIssued; }
    public void setAccessTokenIssued(Boolean value) { accessTokenIssued = value; }
    public Boolean getRuntimeTokenIssued() { return runtimeTokenIssued; }
    public void setRuntimeTokenIssued(Boolean value) { runtimeTokenIssued = value; }
    public Boolean getTaskTokenIssued() { return taskTokenIssued; }
    public void setTaskTokenIssued(Boolean value) { taskTokenIssued = value; }
    public Boolean getTaskCreated() { return taskCreated; }
    public void setTaskCreated(Boolean value) { taskCreated = value; }
    public Boolean getContextCreated() { return contextCreated; }
    public void setContextCreated(Boolean value) { contextCreated = value; }
    public Boolean getSessionCreated() { return sessionCreated; }
    public void setSessionCreated(Boolean value) { sessionCreated = value; }
    public Boolean getWorkerCommandDispatched() { return workerCommandDispatched; }
    public void setWorkerCommandDispatched(Boolean value) { workerCommandDispatched = value; }
    public Boolean getModelDispatched() { return modelDispatched; }
    public void setModelDispatched(Boolean value) { modelDispatched = value; }
    public Boolean getBusinessFunctionDispatched() { return businessFunctionDispatched; }
    public void setBusinessFunctionDispatched(Boolean value) { businessFunctionDispatched = value; }
    public Boolean getRetryTriggered() { return retryTriggered; }
    public void setRetryTriggered(Boolean value) { retryTriggered = value; }
    public Boolean getRecoveryTriggered() { return recoveryTriggered; }
    public void setRecoveryTriggered(Boolean value) { recoveryTriggered = value; }
    public Boolean getTerminationTriggered() { return terminationTriggered; }
    public void setTerminationTriggered(Boolean value) { terminationTriggered = value; }
    public Boolean getReconciliationTriggered() { return reconciliationTriggered; }
    public void setReconciliationTriggered(Boolean value) { reconciliationTriggered = value; }
    public Boolean getProvisioningResourceChanged() { return provisioningResourceChanged; }
    public void setProvisioningResourceChanged(Boolean value) { provisioningResourceChanged = value; }
}
