package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RuntimeTaskClosureDTO {
    private String clientRequestId;
    private String operation;
    private String taskId;
    private Boolean dryRun;
    private Boolean alreadyTerminal;
    private Boolean terminationDispatched;
    private Boolean idempotentReplay;
    private Boolean reconcileRequired;
    private Boolean reconciliationChanged;
    private Boolean alreadyConsistent;
    private String durableEvidence;
    private String sanitizedErrorCode;
    private RuntimeTaskFactsDTO taskFacts;
    private RuntimeAuditSideEffectsDTO auditSideEffects;
    private Boolean newTaskCreated;
    private Boolean newContextCreated;
    private Boolean newSessionCreated;
    private Boolean accessTokenIssued;
    private Boolean runtimeTokenIssued;
    private Boolean taskTokenIssued;
    private Boolean modelRedispatched;
    private Boolean businessFunctionDispatched;
    private Boolean retryTriggered;
    private Boolean recoveryTriggered;
    private Boolean provisioningResourceChanged;
}
