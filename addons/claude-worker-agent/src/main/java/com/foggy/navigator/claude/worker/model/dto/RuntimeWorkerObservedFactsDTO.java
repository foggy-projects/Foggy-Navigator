package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/** Content-free Worker/provider observations, distinct from Navigator state. */
@Data
@Builder
public class RuntimeWorkerObservedFactsDTO {
    private Boolean workerReachable;
    private OffsetDateTime workerObservedAt;
    private Boolean workerTaskKnown;
    private String workerTaskState;
    private Boolean providerProcessPresent;
    private String providerProcessState;
    private Boolean providerActiveTaskPresent;
    private Boolean providerTaskTerminal;
    private String providerTerminalStatus;
    private OffsetDateTime lastHeartbeatAt;
    private OffsetDateTime lastProgressAt;
    private OffsetDateTime processExitedAt;
}
