package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

@Data
public class WorkerGatewayFunctionSummaryDTO {
    private String functionId;
    private String version;
    private String domain;
    private String name;
    private String description;
    private String riskLevel;
    private Boolean approvalRequired;
    private String llmVisibleSummary;
    private String schemaVisibleSummary;
}
