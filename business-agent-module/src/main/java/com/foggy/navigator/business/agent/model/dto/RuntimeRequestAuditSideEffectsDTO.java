package com.foggy.navigator.business.agent.model.dto;

import lombok.Builder;
import lombok.Data;

/** Side effects caused by the audit query itself. Runtime audit queries are strictly read-only. */
@Data
@Builder
public class RuntimeRequestAuditSideEffectsDTO {
    private Boolean accessTokenIssued;
    private Boolean runtimeTokenIssued;
    private Boolean taskTokenIssued;
    private Boolean taskCreated;
    private Boolean contextCreated;
    private Boolean sessionCreated;
    private Boolean modelDispatched;
    private Boolean businessFunctionDispatched;
    private Boolean recoveryTriggered;
    private Boolean provisioningResourceChanged;
}
