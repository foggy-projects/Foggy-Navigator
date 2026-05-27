package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 任务（ask/poll 返回）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTask {
    private String taskId;
    private String agentId;
    private String status;
    private String contextId;
    private String workerTaskId;
    private String providerTaskId;
    private Integer lastAckedSeq;
    private String modelConfigId;
    private String modelConfigSource;
    private String workerBackend;
    private String providerType;
    private String taskSource;
    private String workerSource;
    private String backendSource;
    private String failureStage;
    private String failureSummary;
    private String result;
    private String errorMessage;
    private Long durationMs;
    private BigDecimal costUsd;
    private LocalDateTime createdAt;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public String getWorkerTaskId() { return workerTaskId; }
    public void setWorkerTaskId(String workerTaskId) { this.workerTaskId = workerTaskId; }
    public String getProviderTaskId() { return providerTaskId; }
    public void setProviderTaskId(String providerTaskId) { this.providerTaskId = providerTaskId; }
    public Integer getLastAckedSeq() { return lastAckedSeq; }
    public void setLastAckedSeq(Integer lastAckedSeq) { this.lastAckedSeq = lastAckedSeq; }
    public String getModelConfigId() { return modelConfigId; }
    public void setModelConfigId(String modelConfigId) { this.modelConfigId = modelConfigId; }
    public String getModelConfigSource() { return modelConfigSource; }
    public void setModelConfigSource(String modelConfigSource) { this.modelConfigSource = modelConfigSource; }
    public String getWorkerBackend() { return workerBackend; }
    public void setWorkerBackend(String workerBackend) { this.workerBackend = workerBackend; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public String getTaskSource() { return taskSource; }
    public void setTaskSource(String taskSource) { this.taskSource = taskSource; }
    public String getWorkerSource() { return workerSource; }
    public void setWorkerSource(String workerSource) { this.workerSource = workerSource; }
    public String getBackendSource() { return backendSource; }
    public void setBackendSource(String backendSource) { this.backendSource = backendSource; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String failureStage) { this.failureStage = failureStage; }
    public String getFailureSummary() { return failureSummary; }
    public void setFailureSummary(String failureSummary) { this.failureSummary = failureSummary; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /** 任务是否已终态 */
    public boolean isTerminal() {
        return "COMPLETED".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status) || "CANCELED".equals(status);
    }

    @Override
    public String toString() {
        return "AgentTask{taskId='" + taskId + "', status='" + status + "', contextId='" + contextId + "'}";
    }
}
