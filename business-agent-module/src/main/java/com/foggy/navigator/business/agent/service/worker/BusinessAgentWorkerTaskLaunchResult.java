package com.foggy.navigator.business.agent.service.worker;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BusinessAgentWorkerTaskLaunchResult {
    private String workerTaskId;
    private String workerSessionId;
    private String contextId;
    private String workerId;
    private String providerType;
}
