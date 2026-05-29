package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskDiagnostics {
    private String taskId;
    private String agentId;
    private String contextId;
    private String status;
    private boolean terminal;
    private String terminalStatus;
    private LocalDateTime submittedAt;
    private LocalDateTime workerStartedAt;
    private LocalDateTime lastObservedAt;
    private Long messagesCount;
    private String workerTaskId;
    private String providerTaskId;
    private Long lastAckedSeq;
    private String modelConfigId;
    private String modelConfigSource;
    private String workerBackend;
    private String providerType;
    private String taskSource;
    private String workerSource;
    private String backendSource;
    private String safeWorkerRef;
    private String failureStage;
    private String failureSummary;
    private CancelCapability cancelCapability;
    private Correlation correlation;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isTerminal() { return terminal; }
    public void setTerminal(boolean terminal) { this.terminal = terminal; }
    public String getTerminalStatus() { return terminalStatus; }
    public void setTerminalStatus(String terminalStatus) { this.terminalStatus = terminalStatus; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public LocalDateTime getWorkerStartedAt() { return workerStartedAt; }
    public void setWorkerStartedAt(LocalDateTime workerStartedAt) { this.workerStartedAt = workerStartedAt; }
    public LocalDateTime getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(LocalDateTime lastObservedAt) { this.lastObservedAt = lastObservedAt; }
    public Long getMessagesCount() { return messagesCount; }
    public void setMessagesCount(Long messagesCount) { this.messagesCount = messagesCount; }
    public String getWorkerTaskId() { return workerTaskId; }
    public void setWorkerTaskId(String workerTaskId) { this.workerTaskId = workerTaskId; }
    public String getProviderTaskId() { return providerTaskId; }
    public void setProviderTaskId(String providerTaskId) { this.providerTaskId = providerTaskId; }
    public Long getLastAckedSeq() { return lastAckedSeq; }
    public void setLastAckedSeq(Long lastAckedSeq) { this.lastAckedSeq = lastAckedSeq; }
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
    public String getSafeWorkerRef() { return safeWorkerRef; }
    public void setSafeWorkerRef(String safeWorkerRef) { this.safeWorkerRef = safeWorkerRef; }
    public String getFailureStage() { return failureStage; }
    public void setFailureStage(String failureStage) { this.failureStage = failureStage; }
    public String getFailureSummary() { return failureSummary; }
    public void setFailureSummary(String failureSummary) { this.failureSummary = failureSummary; }
    public CancelCapability getCancelCapability() { return cancelCapability; }
    public void setCancelCapability(CancelCapability cancelCapability) { this.cancelCapability = cancelCapability; }
    public Correlation getCorrelation() { return correlation; }
    public void setCorrelation(Correlation correlation) { this.correlation = correlation; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CancelCapability {
        private Boolean cancelSupported;
        private String cancelMode;
        private Boolean cleanupSupported;
        private List<String> backendLimitations;

        public Boolean getCancelSupported() { return cancelSupported; }
        public void setCancelSupported(Boolean cancelSupported) { this.cancelSupported = cancelSupported; }
        public String getCancelMode() { return cancelMode; }
        public void setCancelMode(String cancelMode) { this.cancelMode = cancelMode; }
        public Boolean getCleanupSupported() { return cleanupSupported; }
        public void setCleanupSupported(Boolean cleanupSupported) { this.cleanupSupported = cleanupSupported; }
        public List<String> getBackendLimitations() { return backendLimitations; }
        public void setBackendLimitations(List<String> backendLimitations) { this.backendLimitations = backendLimitations; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Correlation {
        private String originalTaskId;
        private String recoveryCorrelationKey;
        private Integer attemptNumber;
        private String idempotencyKey;

        public String getOriginalTaskId() { return originalTaskId; }
        public void setOriginalTaskId(String originalTaskId) { this.originalTaskId = originalTaskId; }
        public String getRecoveryCorrelationKey() { return recoveryCorrelationKey; }
        public void setRecoveryCorrelationKey(String recoveryCorrelationKey) { this.recoveryCorrelationKey = recoveryCorrelationKey; }
        public Integer getAttemptNumber() { return attemptNumber; }
        public void setAttemptNumber(Integer attemptNumber) { this.attemptNumber = attemptNumber; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    }
}
