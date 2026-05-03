package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

@Data
public class WorkerGatewayFunctionSchemaDTO {
    private String functionId;
    private String version;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String riskLevel;
    private Boolean approvalRequired;
    private String llmVisibleSummary;
    private String schemaVisibleSummary;
}
