package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class CreateBusinessAgentTaskForm {
    private String clientAppId;
    private String sessionId;
    private String upstreamUserId;
    private String skillId;
    private String workerPoolId;
    private String requestedModelConfigId;
    private String resumeFromTaskId;
}
