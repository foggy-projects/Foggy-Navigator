package com.foggy.navigator.business.agent.service.worker;

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
    private String upstreamUserId;
    private String skillId;
    private String skillName;
    private String workerPoolId;
    private String workerBackend;
    private String modelConfigId;
    private String visionModelConfigId;
    private String markdownBody;
    private String taskScopedToken;
    private String workdir;
    private List<String> allowedDirs;
    private List<String> allowedTools;
}
