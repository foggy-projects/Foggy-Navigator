package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class E2eModelConfigEnsureResultDTO {
    private String clientAppId;
    private String standard;
    private String mockBaseUrl;
    private String modelConfigId;
    private String modelConfigName;
    private boolean modelCreated;
    private boolean modelUpdated;
    private Long grantId;
    private boolean grantCreated;
    private String grantStatus;
    private Boolean isDefault;

    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getStandard() { return standard; }
    public void setStandard(String standard) { this.standard = standard; }
    public String getMockBaseUrl() { return mockBaseUrl; }
    public void setMockBaseUrl(String mockBaseUrl) { this.mockBaseUrl = mockBaseUrl; }
    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; }
    public String getModelConfigName() { return modelConfigName; }
    public void setModelConfigName(String modelConfigName) { this.modelConfigName = modelConfigName; }
    public boolean isModelCreated() { return modelCreated; }
    public void setModelCreated(boolean modelCreated) { this.modelCreated = modelCreated; }
    public boolean isModelUpdated() { return modelUpdated; }
    public void setModelUpdated(boolean modelUpdated) { this.modelUpdated = modelUpdated; }
    public Long getGrantId() { return grantId; }
    public void setGrantId(Long grantId) { this.grantId = grantId; }
    public boolean isGrantCreated() { return grantCreated; }
    public void setGrantCreated(boolean grantCreated) { this.grantCreated = grantCreated; }
    public String getGrantStatus() { return grantStatus; }
    public void setGrantStatus(String grantStatus) { this.grantStatus = grantStatus; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
}
