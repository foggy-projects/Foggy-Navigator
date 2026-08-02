package com.foggy.navigator.claude.worker.model.dto;

import com.foggy.navigator.claude.worker.model.enums.RuntimeTaskTerminalCleanupRepairOutcome;
import lombok.Builder;
import lombok.Data;

/** Typed result of the dedicated terminal-cleanup repair endpoint. */
@Data
@Builder
public class RuntimeTaskTerminalCleanupRepairDTO {
    private String clientRequestId;
    private String operation;
    private String taskId;
    private Boolean dryRun;
    private RuntimeTaskTerminalCleanupRepairOutcome outcome;
    private String reasonCode;
    private String currentTaskStatus;
    private Boolean canonicalTerminal;
    private Boolean terminalTombstonePresent;
    private Boolean lifecycleCleanupComplete;
    private String taskTokenStatus;
    private Boolean activeTaskRegistrationPresent;
    private String selectedPhysicalWorkerId;
    private Boolean repairAllowed;
    private Boolean repairAccepted;
    private Boolean idempotentReplay;
    private Boolean requestReceiptPersisted;
    private RuntimeTaskFactsDTO taskFacts;
    private RuntimeAuditSideEffectsDTO auditSideEffects;

    // Explicitly prove that this repair did not have provider/runtime effects.
    private Boolean workerCommandDispatched;
    private Boolean runtimeDispatched;
    private Boolean modelDispatched;
    private Boolean businessFunctionDispatched;
    private Boolean terminationTriggered;
    private Boolean retryTriggered;
    private Boolean recoveryTriggered;
    private Boolean newTaskCreated;
    private Boolean newContextCreated;
    private Boolean newSessionCreated;
    private Boolean accessTokenIssued;
    private Boolean runtimeTokenIssued;
    private Boolean taskTokenIssued;
    private Boolean provisioningResourceChanged;
}
