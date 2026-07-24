package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class RuntimeBindingAuditDTO {
    private Instant observedAt;
    private String tenant;
    private String upstreamUserId;
    private String agentCode;
    private Boolean agentEnabled;
    private String modelConfigId;
    private String modelVariant;
    private String modelBackend;
    private String directoryId;
    private Boolean directoryEnabled;
    private String workerHost;
    private String physicalWorkerId;
    private String physicalWorkerStatus;
    private Integer directoryRolePort;
    private Integer codexRolePort;
    private String codexRoleSource;
    private Boolean codexRoleSamePhysicalWorker;
    private Long activeTaskCount;
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
