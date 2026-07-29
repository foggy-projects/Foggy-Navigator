package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed termination readiness returned by Navigator.
 *
 * <p>The selected Worker is always resolved from the durable task binding;
 * {@code expectedPhysicalWorkerId} is only an equality fence.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeTerminationReadinessDTO {
    private String taskId;
    private String expectedPhysicalWorkerId;
    private String selectedPhysicalWorkerId;
    private RuntimeWorkerIdentityMatch workerIdentityMatch = RuntimeWorkerIdentityMatch.UNKNOWN;
    private RuntimeTerminationCapability terminationCapability = RuntimeTerminationCapability.UNKNOWN;
    private String currentTaskStatus = "UNKNOWN";
    private Boolean canonicalTerminal;
    private String reasonCode = "UNKNOWN";
    private Boolean terminateAllowed;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getExpectedPhysicalWorkerId() {
        return expectedPhysicalWorkerId;
    }

    public void setExpectedPhysicalWorkerId(String expectedPhysicalWorkerId) {
        this.expectedPhysicalWorkerId = expectedPhysicalWorkerId;
    }

    public String getSelectedPhysicalWorkerId() {
        return selectedPhysicalWorkerId;
    }

    public void setSelectedPhysicalWorkerId(String selectedPhysicalWorkerId) {
        this.selectedPhysicalWorkerId = selectedPhysicalWorkerId;
    }

    public RuntimeWorkerIdentityMatch getWorkerIdentityMatch() {
        return workerIdentityMatch == null ? RuntimeWorkerIdentityMatch.UNKNOWN : workerIdentityMatch;
    }

    public void setWorkerIdentityMatch(RuntimeWorkerIdentityMatch workerIdentityMatch) {
        this.workerIdentityMatch = workerIdentityMatch == null
                ? RuntimeWorkerIdentityMatch.UNKNOWN : workerIdentityMatch;
    }

    public RuntimeTerminationCapability getTerminationCapability() {
        return terminationCapability == null
                ? RuntimeTerminationCapability.UNKNOWN : terminationCapability;
    }

    public void setTerminationCapability(RuntimeTerminationCapability terminationCapability) {
        this.terminationCapability = terminationCapability == null
                ? RuntimeTerminationCapability.UNKNOWN : terminationCapability;
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

    public Boolean getTerminateAllowed() {
        return terminateAllowed;
    }

    public void setTerminateAllowed(Boolean terminateAllowed) {
        this.terminateAllowed = terminateAllowed;
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
