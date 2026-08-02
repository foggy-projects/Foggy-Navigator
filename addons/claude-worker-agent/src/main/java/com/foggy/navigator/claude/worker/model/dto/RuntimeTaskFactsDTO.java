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
    /** Canonical lifecycle terminal fact when the lifecycle projection is available. */
    private Boolean lifecycleCanonicalTerminal;
    /** Monotonic lifecycle tombstone fact; null means the projection was unavailable. */
    private Boolean terminalTombstonePresent;
    /** Durable lifecycle cleanup completeness; distinct from canonical terminal. */
    private Boolean lifecycleCleanupComplete;
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
