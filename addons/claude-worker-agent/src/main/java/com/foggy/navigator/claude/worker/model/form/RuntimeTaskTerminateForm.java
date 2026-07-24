package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

@Data
public class RuntimeTaskTerminateForm {
    private String taskId;
    private String expectedPhysicalWorkerId;
    private String reason;
    private String confirmTaskId;
    private Boolean dryRun;
}
