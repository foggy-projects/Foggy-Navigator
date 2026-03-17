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
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status);
    }

    @Override
    public String toString() {
        return "AgentTask{taskId='" + taskId + "', status='" + status + "', contextId='" + contextId + "'}";
    }
}
