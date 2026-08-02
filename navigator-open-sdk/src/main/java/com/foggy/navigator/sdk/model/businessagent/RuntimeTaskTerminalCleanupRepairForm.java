package com.foggy.navigator.sdk.model.businessagent;

/**
 * Typed request for the dedicated terminal-cleanup repair route.
 *
 * <p>The task and expected Worker are equality fences only. Navigator derives
 * terminal, tombstone, token, registration, and all provider-effect facts
 * from durable server state. The request id is carried exclusively in
 * {@code X-Navigator-Client-Request-Id}.</p>
 */
public class RuntimeTaskTerminalCleanupRepairForm {
    private String taskId;
    private String expectedPhysicalWorkerId;
    private Boolean dryRun;
    private String confirmTaskId;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getExpectedPhysicalWorkerId() { return expectedPhysicalWorkerId; }
    public void setExpectedPhysicalWorkerId(String expectedPhysicalWorkerId) {
        this.expectedPhysicalWorkerId = expectedPhysicalWorkerId;
    }
    public Boolean getDryRun() { return dryRun; }
    public void setDryRun(Boolean dryRun) { this.dryRun = dryRun; }
    public String getConfirmTaskId() { return confirmTaskId; }
    public void setConfirmTaskId(String confirmTaskId) { this.confirmTaskId = confirmTaskId; }
}
