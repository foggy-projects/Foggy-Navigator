package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "task_terminal_tombstones")
public class TaskTerminalTombstoneEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String taskId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 32, nullable = false)
    private String providerType;

    @Column(length = 64)
    private String tenantId;

    @Column(length = 128, nullable = false)
    private String providerTaskId;

    @Column(length = 64)
    private String providerTaskUserId;

    @Column(length = 64)
    private String sourceAgentId;

    @Column(length = 64)
    private String operationId;

    @Column(length = 96)
    private String clientRequestId;

    @Column(length = 32, nullable = false)
    private String terminalOutcome;

    @Column(length = 48, nullable = false)
    private String terminalSource;

    @Column(length = 96, nullable = false, unique = true)
    private String terminalFactId;

    @Column(length = 96, nullable = false)
    private String writerGenerationId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @PrePersist
    void create() {
        if (recordedAt == null) recordedAt = LocalDateTime.now();
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTaskId() { return taskId; }
    public String getSessionId() { return sessionId; }
    public String getProviderType() { return providerType; }
    public String getTenantId() { return tenantId; }
    public String getProviderTaskId() { return providerTaskId; }
    public String getProviderTaskUserId() { return providerTaskUserId; }
    public String getSourceAgentId() { return sourceAgentId; }
    public String getOperationId() { return operationId; }
    public String getClientRequestId() { return clientRequestId; }
    public String getTerminalOutcome() { return terminalOutcome; }
    public String getTerminalFactId() { return terminalFactId; }

    public void setSessionId(String value) { sessionId = value; }
    public void setProviderType(String value) { providerType = value; }
    public void setTenantId(String value) { tenantId = value; }
    public void setProviderTaskId(String value) { providerTaskId = value; }
    public void setProviderTaskUserId(String value) { providerTaskUserId = value; }
    public void setSourceAgentId(String value) { sourceAgentId = value; }
    public void setOperationId(String value) { operationId = value; }
    public void setClientRequestId(String value) { clientRequestId = value; }

    public void setTerminalOutcome(String terminalOutcome) {
        this.terminalOutcome = terminalOutcome;
    }

    public void setTerminalSource(String terminalSource) {
        this.terminalSource = terminalSource;
    }

    public void setTerminalFactId(String terminalFactId) {
        this.terminalFactId = terminalFactId;
    }

    public void setWriterGenerationId(String writerGenerationId) {
        this.writerGenerationId = writerGenerationId;
    }
}
