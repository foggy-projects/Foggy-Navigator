package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class ImportBusinessFunctionManifestForm {
    private String functionId;
    private String businessObjectId;
    private String version;
    private String domain;
    private String name;
    private String description;
    private String exposure;
    private String riskLevel;
    private Boolean approvalRequired;
    private Boolean idempotencyRequired;

    private String manifestJson;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String llmVisibleSummary;
    private String schemaVisibleSummary;
    private String adapterConfigJson;

    private String status;
}
