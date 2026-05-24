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
    private String defaultModelConfigId;
    private String effectiveModelConfigId;
    private String effectiveModelName;
    private String effectiveWorkerBackend;
    private String modelConfigSource;
    private String modelCategory;
    private String agentId;
    private String agentOwnerType;
    private String agentOwnerId;
    private String agentSource;
    private String skillId;
    private String workerPoolId;
    private String workerPoolOwnerType;
    private String workerPoolOwnerId;
    private String workerPoolSource;
    private String internalWorkerPoolId;
    private String internalWorkerPoolOwnerType;
    private String internalWorkerPoolOwnerId;
    private String internalWorkerPoolSource;
    private String requestedDirectoryId;
    private String defaultDirectoryId;
    private String effectiveDirectoryId;
    private String effectivePhysicalWorkerId;
    private String workspaceScope;
    private String workspaceResolverType;
    private Boolean workspaceReadOnly;
    private String workspaceSource;
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

    public String getDefaultModelConfigId() { return defaultModelConfigId; }
    public void setDefaultModelConfigId(String defaultModelConfigId) { this.defaultModelConfigId = defaultModelConfigId; }

    public String getEffectiveModelConfigId() { return effectiveModelConfigId; }
    public void setEffectiveModelConfigId(String effectiveModelConfigId) { this.effectiveModelConfigId = effectiveModelConfigId; }

    public String getEffectiveModelName() { return effectiveModelName; }
    public void setEffectiveModelName(String effectiveModelName) { this.effectiveModelName = effectiveModelName; }

    public String getEffectiveWorkerBackend() { return effectiveWorkerBackend; }
    public void setEffectiveWorkerBackend(String effectiveWorkerBackend) { this.effectiveWorkerBackend = effectiveWorkerBackend; }

    public String getModelConfigSource() { return modelConfigSource; }
    public void setModelConfigSource(String modelConfigSource) { this.modelConfigSource = modelConfigSource; }

    public String getModelCategory() { return modelCategory; }
    public void setModelCategory(String modelCategory) { this.modelCategory = modelCategory; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getAgentOwnerType() { return agentOwnerType; }
    public void setAgentOwnerType(String agentOwnerType) { this.agentOwnerType = agentOwnerType; }

    public String getAgentOwnerId() { return agentOwnerId; }
    public void setAgentOwnerId(String agentOwnerId) { this.agentOwnerId = agentOwnerId; }

    public String getAgentSource() { return agentSource; }
    public void setAgentSource(String agentSource) { this.agentSource = agentSource; }

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }

    public String getWorkerPoolId() { return workerPoolId; }
    public void setWorkerPoolId(String workerPoolId) { this.workerPoolId = workerPoolId; }

    public String getWorkerPoolOwnerType() { return workerPoolOwnerType; }
    public void setWorkerPoolOwnerType(String workerPoolOwnerType) { this.workerPoolOwnerType = workerPoolOwnerType; }

    public String getWorkerPoolOwnerId() { return workerPoolOwnerId; }
    public void setWorkerPoolOwnerId(String workerPoolOwnerId) { this.workerPoolOwnerId = workerPoolOwnerId; }

    public String getWorkerPoolSource() { return workerPoolSource; }
    public void setWorkerPoolSource(String workerPoolSource) { this.workerPoolSource = workerPoolSource; }

    public String getInternalWorkerPoolId() { return internalWorkerPoolId; }
    public void setInternalWorkerPoolId(String internalWorkerPoolId) { this.internalWorkerPoolId = internalWorkerPoolId; }

    public String getInternalWorkerPoolOwnerType() { return internalWorkerPoolOwnerType; }
    public void setInternalWorkerPoolOwnerType(String internalWorkerPoolOwnerType) { this.internalWorkerPoolOwnerType = internalWorkerPoolOwnerType; }

    public String getInternalWorkerPoolOwnerId() { return internalWorkerPoolOwnerId; }
    public void setInternalWorkerPoolOwnerId(String internalWorkerPoolOwnerId) { this.internalWorkerPoolOwnerId = internalWorkerPoolOwnerId; }

    public String getInternalWorkerPoolSource() { return internalWorkerPoolSource; }
    public void setInternalWorkerPoolSource(String internalWorkerPoolSource) { this.internalWorkerPoolSource = internalWorkerPoolSource; }

    public String getRequestedDirectoryId() { return requestedDirectoryId; }
    public void setRequestedDirectoryId(String requestedDirectoryId) { this.requestedDirectoryId = requestedDirectoryId; }

    public String getDefaultDirectoryId() { return defaultDirectoryId; }
    public void setDefaultDirectoryId(String defaultDirectoryId) { this.defaultDirectoryId = defaultDirectoryId; }

    public String getEffectiveDirectoryId() { return effectiveDirectoryId; }
    public void setEffectiveDirectoryId(String effectiveDirectoryId) { this.effectiveDirectoryId = effectiveDirectoryId; }

    public String getEffectivePhysicalWorkerId() { return effectivePhysicalWorkerId; }
    public void setEffectivePhysicalWorkerId(String effectivePhysicalWorkerId) { this.effectivePhysicalWorkerId = effectivePhysicalWorkerId; }

    public String getWorkspaceScope() { return workspaceScope; }
    public void setWorkspaceScope(String workspaceScope) { this.workspaceScope = workspaceScope; }

    public String getWorkspaceResolverType() { return workspaceResolverType; }
    public void setWorkspaceResolverType(String workspaceResolverType) { this.workspaceResolverType = workspaceResolverType; }

    public Boolean getWorkspaceReadOnly() { return workspaceReadOnly; }
    public void setWorkspaceReadOnly(Boolean workspaceReadOnly) { this.workspaceReadOnly = workspaceReadOnly; }

    public String getWorkspaceSource() { return workspaceSource; }
    public void setWorkspaceSource(String workspaceSource) { this.workspaceSource = workspaceSource; }

    public List<AgentReadinessCheck> getChecks() { return checks; }
    public void setChecks(List<AgentReadinessCheck> checks) { this.checks = checks; }

    public SkillArtifactLink getSkillArtifact() { return skillArtifact; }
    public void setSkillArtifact(SkillArtifactLink skillArtifact) { this.skillArtifact = skillArtifact; }
}
