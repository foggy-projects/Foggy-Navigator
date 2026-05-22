package com.foggy.navigator.sdk.model.businessagent;

import java.util.List;
import java.util.Map;

public class ClientAppModelConfigForm {
    private String name;
    private String category;
    private String baseUrl;
    private String modelName;
    private String apiKey;
    private Boolean setDefault;
    private Map<String, String> envVars;
    private List<String> availableModels;
    private String runtimeBudgetPresetKey;
    private String runtimeBudgetOverrideJson;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Boolean getSetDefault() { return setDefault; }
    public void setSetDefault(Boolean setDefault) { this.setDefault = setDefault; }
    public Map<String, String> getEnvVars() { return envVars; }
    public void setEnvVars(Map<String, String> envVars) { this.envVars = envVars; }
    public List<String> getAvailableModels() { return availableModels; }
    public void setAvailableModels(List<String> availableModels) { this.availableModels = availableModels; }
    public String getRuntimeBudgetPresetKey() { return runtimeBudgetPresetKey; }
    public void setRuntimeBudgetPresetKey(String runtimeBudgetPresetKey) { this.runtimeBudgetPresetKey = runtimeBudgetPresetKey; }
    public String getRuntimeBudgetOverrideJson() { return runtimeBudgetOverrideJson; }
    public void setRuntimeBudgetOverrideJson(String runtimeBudgetOverrideJson) { this.runtimeBudgetOverrideJson = runtimeBudgetOverrideJson; }
}
