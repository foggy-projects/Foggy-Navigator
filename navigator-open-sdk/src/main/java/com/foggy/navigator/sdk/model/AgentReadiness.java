package com.foggy.navigator.sdk.model;

import java.util.List;

public class AgentReadiness {
    private String overallStatus;
    private String baseUrl;
    private String clientAppId;
    private String clientAppName;
    private String agentCode;
    private String upstreamUserId;
    private String requestedModelConfigId;
    private String effectiveModelConfigId;
    private List<AgentReadinessCheck> checks;
    private SkillArtifactLink skillArtifact;

    public String getOverallStatus() { return overallStatus; }
    public void setOverallStatus(String overallStatus) { this.overallStatus = overallStatus; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }

    public String getClientAppName() { return clientAppName; }
    public void setClientAppName(String clientAppName) { this.clientAppName = clientAppName; }

    public String getAgentCode() { return agentCode; }
    public void setAgentCode(String agentCode) { this.agentCode = agentCode; }

    public String getUpstreamUserId() { return upstreamUserId; }
    public void setUpstreamUserId(String upstreamUserId) { this.upstreamUserId = upstreamUserId; }

    public String getRequestedModelConfigId() { return requestedModelConfigId; }
    public void setRequestedModelConfigId(String requestedModelConfigId) { this.requestedModelConfigId = requestedModelConfigId; }

    public String getEffectiveModelConfigId() { return effectiveModelConfigId; }
    public void setEffectiveModelConfigId(String effectiveModelConfigId) { this.effectiveModelConfigId = effectiveModelConfigId; }

    public List<AgentReadinessCheck> getChecks() { return checks; }
    public void setChecks(List<AgentReadinessCheck> checks) { this.checks = checks; }

    public SkillArtifactLink getSkillArtifact() { return skillArtifact; }
    public void setSkillArtifact(SkillArtifactLink skillArtifact) { this.skillArtifact = skillArtifact; }
}
