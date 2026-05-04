package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
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

    public String getFunctionId() { return functionId; }
    public void setFunctionId(String functionId) { this.functionId = functionId; }
    public String getBusinessObjectId() { return businessObjectId; }
    public void setBusinessObjectId(String businessObjectId) { this.businessObjectId = businessObjectId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getExposure() { return exposure; }
    public void setExposure(String exposure) { this.exposure = exposure; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public Boolean getApprovalRequired() { return approvalRequired; }
    public void setApprovalRequired(Boolean approvalRequired) { this.approvalRequired = approvalRequired; }
    public Boolean getIdempotencyRequired() { return idempotencyRequired; }
    public void setIdempotencyRequired(Boolean idempotencyRequired) { this.idempotencyRequired = idempotencyRequired; }
    public String getManifestJson() { return manifestJson; }
    public void setManifestJson(String manifestJson) { this.manifestJson = manifestJson; }
    public String getInputSchemaJson() { return inputSchemaJson; }
    public void setInputSchemaJson(String inputSchemaJson) { this.inputSchemaJson = inputSchemaJson; }
    public String getOutputSchemaJson() { return outputSchemaJson; }
    public void setOutputSchemaJson(String outputSchemaJson) { this.outputSchemaJson = outputSchemaJson; }
    public String getLlmVisibleSummary() { return llmVisibleSummary; }
    public void setLlmVisibleSummary(String llmVisibleSummary) { this.llmVisibleSummary = llmVisibleSummary; }
    public String getSchemaVisibleSummary() { return schemaVisibleSummary; }
    public void setSchemaVisibleSummary(String schemaVisibleSummary) { this.schemaVisibleSummary = schemaVisibleSummary; }
    public String getAdapterConfigJson() { return adapterConfigJson; }
    public void setAdapterConfigJson(String adapterConfigJson) { this.adapterConfigJson = adapterConfigJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
