package com.foggy.navigator.codex.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CodexRuntimeDTO {
    private String runtimeId;
    private Integer revision;
    private String workerId;
    private String runtimeType;
    private Boolean endpointConfigured;
    private String endpointDisplay;
    private String instanceId;
    private Boolean enabled;
    private String routingPolicy;
    private Integer rolloutPercentage;
    private Integer priority;
    private Long routingEpoch;
    private String readinessStatus;
    private String readinessMessage;
    private String contractVersion;
    private String cliVersion;
    private String schemaDigest;
    private String expectedCliVersion;
    private String expectedSchemaDigest;
    private LocalDateTime lastCapabilityAt;
    private Boolean capabilityFresh;
    private Boolean supportsUltra;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
