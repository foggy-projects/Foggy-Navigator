package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class RuntimeTaskAuditDTO {
    private Instant observedAt;
    private RuntimeTaskFactsDTO taskFacts;
    private RuntimeAuditSideEffectsDTO auditSideEffects;
    private String taskId;
    private Boolean terminal;
    private String status;
    private String sanitizedErrorCode;
    private String taskTokenStatus;
    private Boolean activeTaskRegistrationPresent;
    private Integer dispatchCount;
    private Integer retryCount;
    private Integer recoveryCount;
    private String physicalWorkerId;
    private String modelConfigId;
    private String modelVariant;
    private Integer requestedToolCount;
    private Integer effectiveToolCount;
    private String toolScopeKind;
    private String toolScopeSource;
    private Integer requestedFunctionCount;
    private Integer effectiveFunctionCount;
    private String functionScopeSource;
    private Boolean taskTokenFunctionScopeEmpty;
    private Boolean runtimeDispatched;
    private Boolean taskModelDispatched;
    private Boolean taskBusinessFunctionDispatched;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
    private List<RuntimeTaskAuditStageDTO> terminalStages;
    private Boolean auditAccessTokenIssued;
    private Boolean auditRuntimeTokenIssued;
    private Boolean auditTaskTokenIssued;
    private Boolean taskCreated;
    private Boolean contextCreated;
    private Boolean sessionCreated;
    private Boolean modelDispatched;
    private Boolean businessFunctionDispatched;
    private Boolean recoveryTriggered;
    private Boolean provisioningResourceChanged;
}
