package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed, read-only reconciliation result for an original termination request id.
 *
 * <p>After response loss, callers may query immediately with the original
 * request id. {@code IN_PROGRESS} and {@code ACCEPTED} require further queries,
 * not another termination. {@code REJECTED}, {@code TERMINAL}, and
 * {@code AMBIGUOUS} also prohibit automatic termination retries. A
 * {@code NOT_FOUND} request may be resubmitted only with the exact same request
 * id, after the original HTTP attempt has ended, and within Navigator's
 * configured idempotency retention window. A new request id is never an
 * automatic recovery mechanism.</p>
 *
 * <p>Unknown enum values map to {@code UNKNOWN}; missing status/reason text
 * maps to the string {@code UNKNOWN}; an unknown canonical terminal fact
 * remains {@code null}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeTaskReconciliationDTO {
    private String clientRequestId;
    private String taskId;
    private RuntimeTaskReconciliationState reconciliationState =
            RuntimeTaskReconciliationState.UNKNOWN;
    private RuntimeTaskTerminationOutcome terminationOutcome =
            RuntimeTaskTerminationOutcome.UNKNOWN;
    private String transition = "UNKNOWN";
    private String currentTaskStatus = "UNKNOWN";
    private Boolean canonicalTerminal;
    private String reasonCode = "UNKNOWN";
    private Boolean requestFound;
    private Boolean readOnly;
    private Boolean sameClientRequestIdReplaySafe;
    private Boolean terminationReplayRecommended;
    private Boolean newClientRequestIdAllowed;

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

    public RuntimeTaskReconciliationState getReconciliationState() {
        return reconciliationState == null
                ? RuntimeTaskReconciliationState.UNKNOWN : reconciliationState;
    }

    public void setReconciliationState(RuntimeTaskReconciliationState reconciliationState) {
        this.reconciliationState = reconciliationState == null
                ? RuntimeTaskReconciliationState.UNKNOWN : reconciliationState;
    }

    public RuntimeTaskTerminationOutcome getTerminationOutcome() {
        return terminationOutcome == null
                ? RuntimeTaskTerminationOutcome.UNKNOWN : terminationOutcome;
    }

    public void setTerminationOutcome(RuntimeTaskTerminationOutcome terminationOutcome) {
        this.terminationOutcome = terminationOutcome == null
                ? RuntimeTaskTerminationOutcome.UNKNOWN : terminationOutcome;
    }

    public String getTransition() {
        return normalized(transition);
    }

    public void setTransition(String transition) {
        this.transition = normalized(transition);
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

    public Boolean getRequestFound() {
        return requestFound;
    }

    public void setRequestFound(Boolean requestFound) {
        this.requestFound = requestFound;
    }

    public Boolean getReadOnly() {
        return readOnly;
    }

    public void setReadOnly(Boolean readOnly) {
        this.readOnly = readOnly;
    }

    public Boolean getSameClientRequestIdReplaySafe() {
        return sameClientRequestIdReplaySafe;
    }

    public void setSameClientRequestIdReplaySafe(Boolean sameClientRequestIdReplaySafe) {
        this.sameClientRequestIdReplaySafe = sameClientRequestIdReplaySafe;
    }

    public Boolean getTerminationReplayRecommended() {
        return terminationReplayRecommended;
    }

    public void setTerminationReplayRecommended(Boolean terminationReplayRecommended) {
        this.terminationReplayRecommended = terminationReplayRecommended;
    }

    public Boolean getNewClientRequestIdAllowed() {
        return newClientRequestIdAllowed;
    }

    public void setNewClientRequestIdAllowed(Boolean newClientRequestIdAllowed) {
        this.newClientRequestIdAllowed = newClientRequestIdAllowed;
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
