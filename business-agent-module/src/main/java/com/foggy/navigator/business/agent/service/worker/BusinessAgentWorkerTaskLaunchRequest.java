package com.foggy.navigator.business.agent.service.worker;

import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BusinessAgentWorkerTaskLaunchRequest {
    private String tenantId;
    private String actorUserId;
    private String businessTaskId;
    private String sessionId;
    private String contextId;
    private String clientAppId;
    /** Server-resolved ClientApp upstream scope used for physical Worker visibility checks. */
    private String upstreamSystemId;
    private String upstreamUserId;
    private String agentId;
    private String skillId;
    private String skillName;
    private String workerPoolId;
    private ResourceOwnerType workerPoolOwnerType;
    private String workerPoolOwnerId;
    private String physicalWorkerId;
    /** Server-resolved immutable dispatch target. Never populate from caller input. */
    private String selectedWorkerId;
    /** Server-generated logical authorization lease bound to the task token. */
    private String workerLeaseId;
    private String workerBackend;
    private String modelConfigId;
    private String model;
    private String visionModelConfigId;
    private String directoryId;
    private String workspaceScope;
    private String workspaceResolverType;
    private Boolean workspaceReadOnly;
    private Object workspaceQuotaPolicy;
    private Object workspaceRetentionPolicy;
    private Object workspaceConcurrencyPolicy;
    private String taskScopedToken;
    private String workdir;
    private List<String> allowedDirs;
    private List<String> allowedTools;
}
