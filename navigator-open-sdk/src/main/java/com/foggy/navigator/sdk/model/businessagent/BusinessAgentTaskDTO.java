package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class BusinessAgentTaskDTO {
    private String taskId;
    private String sessionId;
    private String tenantId;
    private String clientAppId;
    private String upstreamUserId;
    private String navigatorEffectiveUserId;
    private String skillId;
    private String workerPoolId;
    private String modelConfigId;
    private String requestedModelConfigId;
    private String model;
    private String requestedModelVariant;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getUpstreamUserId() { return upstreamUserId; }
    public void setUpstreamUserId(String upstreamUserId) { this.upstreamUserId = upstreamUserId; }
    public String getNavigatorEffectiveUserId() { return navigatorEffectiveUserId; }
    public void setNavigatorEffectiveUserId(String navigatorEffectiveUserId) { this.navigatorEffectiveUserId = navigatorEffectiveUserId; }
    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getWorkerPoolId() { return workerPoolId; }
    public void setWorkerPoolId(String workerPoolId) { this.workerPoolId = workerPoolId; }
    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; }
    public String getRequestedModelConfigId() { return requestedModelConfigId; }
    public void setRequestedModelConfigId(String requestedModelConfigId) { this.requestedModelConfigId = requestedModelConfigId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getRequestedModelVariant() { return requestedModelVariant; }
    public void setRequestedModelVariant(String requestedModelVariant) { this.requestedModelVariant = requestedModelVariant; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
