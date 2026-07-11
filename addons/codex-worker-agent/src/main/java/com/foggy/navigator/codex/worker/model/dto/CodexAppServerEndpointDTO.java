package com.foggy.navigator.codex.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CodexAppServerEndpointDTO {
    private String endpointId;
    private String workerId;
    private String endpointUrl;
    private String endpointDisplay;
    private Boolean tokenConfigured;
    private Long configurationVersion;
    private String lastSyncStatus;
    private String lastSyncMessage;
    private LocalDateTime lastSyncedAt;
    private String lastRuntimeId;
    private Integer lastRuntimeRevision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
