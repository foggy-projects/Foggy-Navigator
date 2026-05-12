package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * 会话摘要（会话列表条目）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionSummary {
    private String contextId;
    private String agentId;
    private String title;
    private String status;
    private String latestTaskId;
    private Map<String, Object> clientContext;
    private String createdAt;
    private String updatedAt;

    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLatestTaskId() { return latestTaskId; }
    public void setLatestTaskId(String latestTaskId) { this.latestTaskId = latestTaskId; }
    public Map<String, Object> getClientContext() { return clientContext; }
    public void setClientContext(Map<String, Object> clientContext) { this.clientContext = clientContext; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "SessionSummary{contextId='" + contextId + "', title='" + title + "'}";
    }
}
