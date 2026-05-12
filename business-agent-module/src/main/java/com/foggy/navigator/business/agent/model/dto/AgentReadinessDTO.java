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
    private String effectiveModelConfigId;
    private List<AgentReadinessCheckDTO> checks = new ArrayList<>();
    private SkillArtifactLinkDTO skillArtifact;

    public void refreshOverallStatus() {
        boolean failed = checks != null && checks.stream()
                .anyMatch(check -> "FAIL".equals(check.getStatus()));
        overallStatus = failed ? "FAIL" : "OK";
    }
}
