package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;

public class AgentWorkerBindingDTO {
    private Long id;
    private String tenantId;
    private String agentId;
    private String workerPoolId;
    private String clientAppId;
    private String workerPoolName;
    private String workerBackend;
    private String routingPolicy;
    private String workerPoolOwnerType;
    private String workerPoolOwnerId;
    private Boolean defaultWorkerPool;
    private String status;
    private String healthStatus;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getWorkerPoolId() { return workerPoolId; }
    public void setWorkerPoolId(String workerPoolId) { this.workerPoolId = workerPoolId; }

    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }

    public String getWorkerPoolName() { return workerPoolName; }
    public void setWorkerPoolName(String workerPoolName) { this.workerPoolName = workerPoolName; }

    public String getWorkerBackend() { return workerBackend; }
    public void setWorkerBackend(String workerBackend) { this.workerBackend = workerBackend; }

    public String getRoutingPolicy() { return routingPolicy; }
    public void setRoutingPolicy(String routingPolicy) { this.routingPolicy = routingPolicy; }

    public String getWorkerPoolOwnerType() { return workerPoolOwnerType; }
    public void setWorkerPoolOwnerType(String workerPoolOwnerType) { this.workerPoolOwnerType = workerPoolOwnerType; }

    public String getWorkerPoolOwnerId() { return workerPoolOwnerId; }
    public void setWorkerPoolOwnerId(String workerPoolOwnerId) { this.workerPoolOwnerId = workerPoolOwnerId; }

    public Boolean getDefaultWorkerPool() { return defaultWorkerPool; }
    public void setDefaultWorkerPool(Boolean defaultWorkerPool) { this.defaultWorkerPool = defaultWorkerPool; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
