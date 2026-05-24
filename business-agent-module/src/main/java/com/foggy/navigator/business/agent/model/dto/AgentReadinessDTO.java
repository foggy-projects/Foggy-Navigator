package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentReadinessDTO {
    private String overallStatus;
    private String baseUrl;
    private String clientAppId;
    private String clientAppName;
    private String agentCode;
    private String upstreamUserId;
    private String requestedModelConfigId;
    private String requestedModelVariant;
    private String defaultModelConfigId;
    private String defaultModelName;
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
    private List<AgentReadinessCheckDTO> checks = new ArrayList<>();
    private SkillArtifactLinkDTO skillArtifact;

    public void refreshOverallStatus() {
        boolean failed = checks != null && checks.stream()
                .anyMatch(check -> "FAIL".equals(check.getStatus()));
        overallStatus = failed ? "FAIL" : "OK";
    }
}
