package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BusinessAgentBundleDTO {
    private String tenantId;
    private String clientAppId;
    private String agentId;
    private String skillId;
    private String name;
    private String description;
    private String agentType;
    private String workerId;
    private String defaultModelConfigId;
    private String defaultModel;
    private SkillBundleDTO skillBundle;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getDefaultModelConfigId() { return defaultModelConfigId; }
    public void setDefaultModelConfigId(String defaultModelConfigId) { this.defaultModelConfigId = defaultModelConfigId; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public SkillBundleDTO getSkillBundle() { return skillBundle; }
    public void setSkillBundle(SkillBundleDTO skillBundle) { this.skillBundle = skillBundle; }
}
