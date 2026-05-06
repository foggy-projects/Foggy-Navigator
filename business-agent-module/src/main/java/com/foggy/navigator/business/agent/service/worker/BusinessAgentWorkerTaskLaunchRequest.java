package com.foggy.navigator.business.agent.service.worker;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BusinessAgentWorkerTaskLaunchRequest {
    private String tenantId;
    private String actorUserId;
    private String businessTaskId;
    private String sessionId;
    private String clientAppId;
    private String upstreamUserId;
    private String skillId;
    private String workerPoolId;
    private String workerBackend;
    private String modelConfigId;
    private String markdownBody;
}
