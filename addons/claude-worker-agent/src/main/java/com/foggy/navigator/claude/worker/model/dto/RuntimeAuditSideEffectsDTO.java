package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

/** Side effects caused by the audit/readiness request itself. */
@Data
@Builder
public class RuntimeAuditSideEffectsDTO {
    private Boolean accessTokenIssued;
    private Boolean runtimeTokenIssued;
    private Boolean taskTokenIssued;
    private Boolean taskCreated;
    private Boolean contextCreated;
    private Boolean sessionCreated;
    private Boolean workerCommandDispatched;
    private Boolean modelDispatched;
    private Boolean businessFunctionDispatched;
    private Boolean retryTriggered;
    private Boolean recoveryTriggered;
    private Boolean terminationTriggered;
    private Boolean reconciliationTriggered;
    private Boolean provisioningResourceChanged;
}
