package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateBusinessAgentTaskForm {
    private String clientAppId;
    private String sessionId;
    private String upstreamUserId;
    private String skillId;
    private String workerPoolId;
    private String requestedModelConfigId;
    private String resumeFromTaskId;

    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUpstreamUserId() { return upstreamUserId; }
    public void setUpstreamUserId(String upstreamUserId) { this.upstreamUserId = upstreamUserId; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getWorkerPoolId() { return workerPoolId; }
    public void setWorkerPoolId(String workerPoolId) { this.workerPoolId = workerPoolId; }
    public String getRequestedModelConfigId() { return requestedModelConfigId; }
    public void setRequestedModelConfigId(String requestedModelConfigId) { this.requestedModelConfigId = requestedModelConfigId; }
    public String getResumeFromTaskId() { return resumeFromTaskId; }
    public void setResumeFromTaskId(String resumeFromTaskId) { this.resumeFromTaskId = resumeFromTaskId; }
}
