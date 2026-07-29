package com.foggy.navigator.claude.worker.model.dto;

import com.foggy.navigator.claude.worker.model.enums.RuntimeTerminationCapability;
import com.foggy.navigator.claude.worker.model.enums.RuntimeWorkerIdentityMatch;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RuntimeTerminationReadinessDTO {
    private Boolean taskExists;
    private String taskId;
    private String expectedPhysicalWorkerId;
    private String selectedPhysicalWorkerId;
    private RuntimeWorkerIdentityMatch workerIdentityMatch;
    private RuntimeTerminationCapability terminationCapability;
    private String currentTaskStatus;
    private Boolean canonicalTerminal;
    private String reasonCode;
    private Boolean terminationRequestReceiptEnabled;
    private Boolean terminal;
    private String status;
    private String physicalWorkerId;
    private Boolean workerReachable;
    private Boolean workerActiveTaskPresent;
    private Boolean terminationReady;
    private Boolean terminationAuthConfigured;
    private Boolean terminationWorkerIdConfigured;
    private String taskTokenStatus;
    private Boolean activeTaskRegistrationPresent;
    private Boolean terminateAllowed;
    private String blockedReason;
    private Boolean dryRun;
    private RuntimeTaskFactsDTO taskFacts;
    private RuntimeAuditSideEffectsDTO auditSideEffects;
}
