package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
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

    public String getFunctionId() { return functionId; }
    public void setFunctionId(String functionId) { this.functionId = functionId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public Boolean getApprovalRequired() { return approvalRequired; }
    public void setApprovalRequired(Boolean approvalRequired) { this.approvalRequired = approvalRequired; }
    public Boolean getIdempotencyRequired() { return idempotencyRequired; }
    public void setIdempotencyRequired(Boolean idempotencyRequired) { this.idempotencyRequired = idempotencyRequired; }
    public String getLlmVisibleSummary() { return llmVisibleSummary; }
    public void setLlmVisibleSummary(String llmVisibleSummary) { this.llmVisibleSummary = llmVisibleSummary; }
    public String getSchemaVisibleSummary() { return schemaVisibleSummary; }
    public void setSchemaVisibleSummary(String schemaVisibleSummary) { this.schemaVisibleSummary = schemaVisibleSummary; }
}
