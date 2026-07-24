package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class RuntimeTaskAuditDTO {
    private Instant observedAt;
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
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
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
