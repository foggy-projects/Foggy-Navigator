package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RotateModelConfigKeyForm {
    private String apiKey;
    private Boolean clearApiKey;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Boolean getClearApiKey() { return clearApiKey; }
    public void setClearApiKey(Boolean clearApiKey) { this.clearApiKey = clearApiKey; }
}
