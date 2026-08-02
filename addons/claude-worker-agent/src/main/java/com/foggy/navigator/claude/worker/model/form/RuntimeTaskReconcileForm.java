package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * A task-only body selects typed, read-only termination-request reconciliation.
 * Explicit legacy projection-repair fields retain their existing deprecated
 * branch; new terminal cleanup repair uses its dedicated typed route instead.
 */
@Data
public class RuntimeTaskReconcileForm {
    private String taskId;
    private String expectedPhysicalWorkerId;
    private Integer expectedDispatchCount;
    private String confirmTaskId;
    private Boolean dryRun;

    /**
     * The historical mutation branch remains reachable only through explicit
     * legacy fields. New typed callers never use this branch.
     */
    public boolean isLegacyProjectionRepairRequest() {
        return expectedPhysicalWorkerId != null
                || expectedDispatchCount != null
                || confirmTaskId != null
                || dryRun != null;
    }
}
