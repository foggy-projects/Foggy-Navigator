package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** Durable facts about the queried task, distinct from audit request effects. */
@Data
@Builder
public class RuntimeTaskFactsDTO {
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
    private Boolean modelDispatched;
    private Boolean businessFunctionDispatched;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
    private List<RuntimeTaskAuditStageDTO> stages;
}
