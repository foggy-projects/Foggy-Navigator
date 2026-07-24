package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

@Data
public class RuntimeTaskReconcileForm {
    private String taskId;
    private String expectedPhysicalWorkerId;
    private Integer expectedDispatchCount;
    private String confirmTaskId;
    private Boolean dryRun;
}
