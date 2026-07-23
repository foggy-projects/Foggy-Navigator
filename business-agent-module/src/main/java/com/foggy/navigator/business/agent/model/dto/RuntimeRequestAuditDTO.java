package com.foggy.navigator.business.agent.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/** Stable, sanitized runtime self-audit response. Nullable booleans mean unknown. */
@Data
@Builder
public class RuntimeRequestAuditDTO {
    private String clientRequestId;
    private String operation;
    private Instant receivedAt;
    private Instant completedAt;
    private Boolean terminal;
    private String result;
    private String sanitizedErrorCode;
    private String safeErrorSummary;
    private Boolean httpRequestReceived;
    private Boolean runtimeTokenRequestReceived;
    private Boolean runtimeTokenIssued;
    private Boolean safeSmokeRequestReceived;
    private Boolean syntheticEvidenceCreated;
    private String taskId;
    private String status;
    private Integer effectiveToolCount;
    private String toolScopeKind;
    private String toolScopeSource;
    private Integer effectiveFunctionCount;
    private String functionScopeSource;
    private Boolean taskTokenFunctionScopeEmpty;
    private String taskTokenStatus;
    private Boolean runtimeDispatched;
    private List<RuntimeRequestAuditStageDTO> stages;
}
