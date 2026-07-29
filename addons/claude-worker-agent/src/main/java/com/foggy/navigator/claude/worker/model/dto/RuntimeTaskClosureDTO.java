package com.foggy.navigator.claude.worker.model.dto;

import com.foggy.navigator.claude.worker.model.enums.RuntimeTaskReconciliationState;
import com.foggy.navigator.claude.worker.model.enums.RuntimeTaskTerminationOutcome;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RuntimeTaskClosureDTO {
    private String clientRequestId;
    private String operation;
    private String taskId;
    private RuntimeTaskTerminationOutcome outcome;
    private RuntimeTaskReconciliationState reconciliationState;
    private RuntimeTaskTerminationOutcome terminationOutcome;
    private String transition;
    private String currentTaskStatus;
    private Boolean canonicalTerminal;
    private String reasonCode;
    private String selectedPhysicalWorkerId;
    private Boolean requestFound;
    private Boolean readOnly;
    private Boolean sameClientRequestIdReplaySafe;
    private Boolean terminationReplayRecommended;
    private Boolean newClientRequestIdAllowed;
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
