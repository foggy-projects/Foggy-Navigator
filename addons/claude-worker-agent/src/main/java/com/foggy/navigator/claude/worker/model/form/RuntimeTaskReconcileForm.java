package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

@Data
public class RuntimeTaskReconcileForm {
    private String taskId;
    private String expectedPhysicalWorkerId;
    private Integer expectedDispatchCount;
    private String confirmTaskId;
    private Boolean dryRun;

    /**
     * The original mutation contract remains available only when one of its
     * legacy projection-repair fields is explicitly present. A body containing
     * only taskId selects typed, read-only request reconciliation.
     */
    public boolean isLegacyProjectionRepairRequest() {
        return expectedPhysicalWorkerId != null
                || expectedDispatchCount != null
                || confirmTaskId != null
                || dryRun != null;
    }
}
