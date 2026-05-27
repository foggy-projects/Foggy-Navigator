package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 任务增量消息分页结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskMessagesPage {
    private String taskId;
    private String contextId;
    private String status;
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
    private List<SessionMessage> messages;
    private String nextCursor;
    private boolean hasMore;
    private boolean terminal;
    private String terminalStatus;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
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
    public List<SessionMessage> getMessages() { return messages; }
    public void setMessages(List<SessionMessage> messages) { this.messages = messages; }
    public String getNextCursor() { return nextCursor; }
    public void setNextCursor(String nextCursor) { this.nextCursor = nextCursor; }
    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
    public boolean isTerminal() { return terminal; }
    public void setTerminal(boolean terminal) { this.terminal = terminal; }
    public String getTerminalStatus() { return terminalStatus; }
    public void setTerminalStatus(String terminalStatus) { this.terminalStatus = terminalStatus; }
}
