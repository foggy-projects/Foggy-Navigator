package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class OpenTaskDiagnosticsDTO {

    private String taskId;

    private String agentId;

    private String contextId;

    private String status;

    private Boolean terminal;

    private String terminalStatus;

    private LocalDateTime submittedAt;

    private LocalDateTime workerStartedAt;

    private LocalDateTime lastObservedAt;

    private Long messagesCount;

    private String workerTaskId;

    private String providerTaskId;

    private Long lastAckedSeq;

    private String modelConfigId;

    private String modelConfigSource;

    private String workerBackend;

    private String providerType;

    private String taskSource;

    private String workerSource;

    private String backendSource;

    private String safeWorkerRef;

    private String failureStage;

    private String failureSummary;

    private OpenTaskCancelCapabilityDTO cancelCapability;

    private OpenTaskCorrelationDTO correlation;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
