package com.foggy.navigator.sdk.model.businessagent;

/**
 * Read-only request body for termination request reconciliation.
 *
 * <p>The original termination request id is carried in
 * {@code X-Navigator-Client-Request-Id}; it is intentionally not duplicated
 * in this body.</p>
 */
public class RuntimeTaskReconcileForm {
    private String taskId;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }
}
