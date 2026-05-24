package com.foggy.navigator.sdk.model.businessagent;

public class UpstreamAgentForm {
    private String agentId;
    private String name;
    private String description;
    private String agentType;
    private String workerId;
    private String defaultDirectoryId;
    private String defaultModelConfigId;
    private String defaultModel;
    private String skillsJson;
    private String agentProfileJson;
    private Boolean enabled;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getDefaultDirectoryId() { return defaultDirectoryId; }
    public void setDefaultDirectoryId(String defaultDirectoryId) { this.defaultDirectoryId = defaultDirectoryId; }
    public String getDefaultModelConfigId() { return defaultModelConfigId; }
    public void setDefaultModelConfigId(String defaultModelConfigId) { this.defaultModelConfigId = defaultModelConfigId; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public String getSkillsJson() { return skillsJson; }
    public void setSkillsJson(String skillsJson) { this.skillsJson = skillsJson; }
    public String getAgentProfileJson() { return agentProfileJson; }
    public void setAgentProfileJson(String agentProfileJson) { this.agentProfileJson = agentProfileJson; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
