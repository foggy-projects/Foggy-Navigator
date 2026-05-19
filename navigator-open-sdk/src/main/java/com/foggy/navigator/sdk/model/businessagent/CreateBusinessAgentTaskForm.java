package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;


@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateBusinessAgentTaskForm {
    private String clientAppId;
    private String sessionId;
    private String contextId;
    private String upstreamUserId;
    private String skillId;
    @JsonProperty("skill_name")
    @JsonAlias({"skillName"})
    private String skillName;
    private String workerPoolId;
    private String requestedModelConfigId;
    private String resumeFromTaskId;
    private String clientContextJson;
    @JsonAlias({"workDir", "working_dir", "workingDirectory", "working_directory"})
    private String workdir;
    @JsonProperty("allowed_dirs")
    @JsonAlias({"allowedDirs", "allowed_directories", "allowedDirectories"})
    private List<String> allowedDirs;
    @JsonProperty("allowed_tools")
    @JsonAlias({"allowedTools", "authorized_tools", "authorizedTools", "tool_allowlist", "toolAllowlist"})
    private List<String> allowedTools;

    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public String getUpstreamUserId() { return upstreamUserId; }
    public void setUpstreamUserId(String upstreamUserId) { this.upstreamUserId = upstreamUserId; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }
    public String getWorkerPoolId() { return workerPoolId; }
    public void setWorkerPoolId(String workerPoolId) { this.workerPoolId = workerPoolId; }
    public String getRequestedModelConfigId() { return requestedModelConfigId; }
    public void setRequestedModelConfigId(String requestedModelConfigId) { this.requestedModelConfigId = requestedModelConfigId; }
    public String getResumeFromTaskId() { return resumeFromTaskId; }
    public void setResumeFromTaskId(String resumeFromTaskId) { this.resumeFromTaskId = resumeFromTaskId; }
    public String getClientContextJson() { return clientContextJson; }
    public void setClientContextJson(String clientContextJson) { this.clientContextJson = clientContextJson; }
    public String getWorkdir() { return workdir; }
    public void setWorkdir(String workdir) { this.workdir = workdir; }
    public List<String> getAllowedDirs() { return allowedDirs; }
    public void setAllowedDirs(List<String> allowedDirs) { this.allowedDirs = allowedDirs; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
}
