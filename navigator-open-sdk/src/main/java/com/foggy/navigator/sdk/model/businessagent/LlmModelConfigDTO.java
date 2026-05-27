package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmModelConfigDTO {
    private String id;
    private String tenantId;
    private String name;
    private String category;
    private String baseUrl;
    private String modelName;
    private Boolean hasApiKey;
    private String scope;
    private List<String> allowedWorkerIds;
    private String workerBackend;
    private Map<String, String> envVars;
    private List<String> availableModels;
    private String runtimeBudgetPresetKey;
    private String runtimeBudgetOverrideJson;
    private String ownerType;
    private String ownerId;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Boolean getHasApiKey() { return hasApiKey; }
    public void setHasApiKey(Boolean hasApiKey) { this.hasApiKey = hasApiKey; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public List<String> getAllowedWorkerIds() { return allowedWorkerIds; }
    public void setAllowedWorkerIds(List<String> allowedWorkerIds) { this.allowedWorkerIds = allowedWorkerIds; }
    public String getWorkerBackend() { return workerBackend; }
    public void setWorkerBackend(String workerBackend) { this.workerBackend = workerBackend; }
    public Map<String, String> getEnvVars() { return envVars; }
    public void setEnvVars(Map<String, String> envVars) { this.envVars = envVars; }
    public List<String> getAvailableModels() { return availableModels; }
    public void setAvailableModels(List<String> availableModels) { this.availableModels = availableModels; }
    public String getRuntimeBudgetPresetKey() { return runtimeBudgetPresetKey; }
    public void setRuntimeBudgetPresetKey(String runtimeBudgetPresetKey) { this.runtimeBudgetPresetKey = runtimeBudgetPresetKey; }
    public String getRuntimeBudgetOverrideJson() { return runtimeBudgetOverrideJson; }
    public void setRuntimeBudgetOverrideJson(String runtimeBudgetOverrideJson) { this.runtimeBudgetOverrideJson = runtimeBudgetOverrideJson; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
