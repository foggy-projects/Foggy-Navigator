package com.foggy.navigator.session.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Safe query projection; deliberately excludes signed capability material. */
@Data
@Builder
public class TerminationOperationDTO {
    private String operationId;
    private Integer schemaVersion;
    private String taskId;
    private String providerTaskId;
    private String providerType;
    private String workerId;
    private String kind;
    private String origin;
    private String actorId;
    private String actorType;
    private String authorizationDecisionId;
    private String reasonCode;
    private String correlationId;
    private Integer expectedPid;
    /** Immutable Worker-issued process/runtime identity paired with expectedPid. */
    private String expectedProcessIdentity;
    private String status;
    private String dispatchState;
    private String attentionCode;
    private String failureCode;
    private LocalDateTime requestedAt;
    private LocalDateTime dispatchedAt;
    private LocalDateTime observedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
