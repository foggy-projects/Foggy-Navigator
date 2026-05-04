package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class GrantModelConfigForm {
    private String modelConfigId;
    private Boolean isDefault;
    private String grantScope;

    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public String getGrantScope() { return grantScope; }
    public void setGrantScope(String grantScope) { this.grantScope = grantScope; }
}
