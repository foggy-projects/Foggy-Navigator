package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * Typed mutation request for an already-terminal task whose durable cleanup
 * facts are incomplete. The client-supplied worker and task id are equality
 * fences only; Navigator resolves all lifecycle facts server-side.
 */
@Data
public class RuntimeTaskTerminalCleanupRepairForm {
    private String taskId;
    private String expectedPhysicalWorkerId;
    private Boolean dryRun;
    private String confirmTaskId;
}
