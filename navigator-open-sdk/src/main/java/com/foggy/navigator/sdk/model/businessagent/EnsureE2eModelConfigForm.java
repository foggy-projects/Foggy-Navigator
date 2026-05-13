package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class EnsureE2eModelConfigForm {
    private String standard;
    private String mockBaseUrl;
    private Boolean setDefault;

    public String getStandard() { return standard; }
    public void setStandard(String standard) { this.standard = standard; }
    public String getMockBaseUrl() { return mockBaseUrl; }
    public void setMockBaseUrl(String mockBaseUrl) { this.mockBaseUrl = mockBaseUrl; }
    public Boolean getSetDefault() { return setDefault; }
    public void setSetDefault(Boolean setDefault) { this.setDefault = setDefault; }
}
