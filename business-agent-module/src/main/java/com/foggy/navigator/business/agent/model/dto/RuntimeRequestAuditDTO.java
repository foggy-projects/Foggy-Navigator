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
    private String parentClientRequestId;
    private String correlationId;
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
    private Integer runtimeTokenExchangeCount;
    private Boolean standardAskRequestReceived;
    private Boolean admissionCompleted;
    private Boolean taskCreated;
    private Boolean taskTokenIssued;
    private Boolean safeSmokeRequestReceived;
    private Boolean syntheticEvidenceCreated;
    private String taskId;
    private String agentCode;
    private String upstreamUserId;
    private String physicalWorkerId;
    private String modelConfigId;
    private String modelVariant;
    private String status;
    private Integer requestedToolCount;
    private Integer effectiveToolCount;
    private String toolScopeKind;
    private String toolScopeSource;
    private Integer requestedFunctionCount;
    private Integer effectiveFunctionCount;
    private String functionScopeSource;
    private Boolean taskTokenFunctionScopeEmpty;
    private String taskTokenStatus;
    private Boolean runtimeDispatched;
    private Boolean modelDispatched;
    private Boolean businessFunctionDispatched;
    private Integer dispatchCount;
    private Integer retryCount;
    private Integer recoveryCount;
    private RuntimeRequestTaskFactsDTO taskFacts;
    private RuntimeRequestAuditSideEffectsDTO auditSideEffects;
    private List<RuntimeRequestAuditStageDTO> stages;
}
