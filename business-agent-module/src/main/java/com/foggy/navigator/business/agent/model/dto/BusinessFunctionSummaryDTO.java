package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

@Data
public class BusinessFunctionSummaryDTO {
    private String functionId;
    private String version;
    private String domain;
    private String name;
    private String description;
    private String riskLevel;
    private Boolean approvalRequired;
    private Boolean idempotencyRequired;
    private String llmVisibleSummary;
    private String schemaVisibleSummary;
}
