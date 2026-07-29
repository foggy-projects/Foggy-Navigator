package com.foggy.navigator.sdk.model.businessagent;

/** Typed request body for {@code POST /api/v1/open/runtime/task-terminate}. */
public class RuntimeTaskTerminateForm {
    private String taskId;
    private String expectedPhysicalWorkerId;
    private String reason;
    private Boolean dryRun;
    private String confirmTaskId;

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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(Boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getConfirmTaskId() {
        return confirmTaskId;
    }

    public void setConfirmTaskId(String confirmTaskId) {
        this.confirmTaskId = confirmTaskId;
    }
}
