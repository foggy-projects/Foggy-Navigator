package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed result of one runtime task termination request.
 *
 * <p>{@code ACCEPTED} records request acceptance only. A caller may treat the
 * task as terminal only when {@code canonicalTerminal} is {@code true};
 * {@code null} means Navigator could not establish the canonical fact.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeTaskTerminationDTO {
    private String clientRequestId;
    private String taskId;
    private RuntimeTaskTerminationOutcome outcome = RuntimeTaskTerminationOutcome.UNKNOWN;
    private String currentTaskStatus = "UNKNOWN";
    private Boolean canonicalTerminal;
    private String reasonCode = "UNKNOWN";
    private String selectedPhysicalWorkerId;
    private Boolean dryRun;
    private Boolean terminationDispatched;
    private Boolean idempotentReplay;
    private Boolean reconcileRequired;

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public RuntimeTaskTerminationOutcome getOutcome() {
        return outcome == null ? RuntimeTaskTerminationOutcome.UNKNOWN : outcome;
    }

    public void setOutcome(RuntimeTaskTerminationOutcome outcome) {
        this.outcome = outcome == null ? RuntimeTaskTerminationOutcome.UNKNOWN : outcome;
    }

    public String getCurrentTaskStatus() {
        return normalized(currentTaskStatus);
    }

    public void setCurrentTaskStatus(String currentTaskStatus) {
        this.currentTaskStatus = normalized(currentTaskStatus);
    }

    public Boolean getCanonicalTerminal() {
        return canonicalTerminal;
    }

    public void setCanonicalTerminal(Boolean canonicalTerminal) {
        this.canonicalTerminal = canonicalTerminal;
    }

    public String getReasonCode() {
        return normalized(reasonCode);
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = normalized(reasonCode);
    }

    public String getSelectedPhysicalWorkerId() {
        return selectedPhysicalWorkerId;
    }

    public void setSelectedPhysicalWorkerId(String selectedPhysicalWorkerId) {
        this.selectedPhysicalWorkerId = selectedPhysicalWorkerId;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(Boolean dryRun) {
        this.dryRun = dryRun;
    }

    public Boolean getTerminationDispatched() {
        return terminationDispatched;
    }

    public void setTerminationDispatched(Boolean terminationDispatched) {
        this.terminationDispatched = terminationDispatched;
    }

    public Boolean getIdempotentReplay() {
        return idempotentReplay;
    }

    public void setIdempotentReplay(Boolean idempotentReplay) {
        this.idempotentReplay = idempotentReplay;
    }

    public Boolean getReconcileRequired() {
        return reconcileRequired;
    }

    public void setReconcileRequired(Boolean reconcileRequired) {
        this.reconcileRequired = reconcileRequired;
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
