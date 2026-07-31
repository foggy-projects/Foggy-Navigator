package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "task_lifecycle_snapshots")
public class TaskLifecycleSnapshotEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String taskId;

    @Column(length = 64)
    private String sessionId;

    @Column(length = 128)
    private String physicalWorkerId;

    @Column(length = 128)
    private String stateGeneration;

    @Column(length = 128)
    private String instanceEpoch;

    @Column(length = 128)
    private String providerTaskId;

    @Column(length = 96)
    private String dispatchId;

    @Column(length = 64)
    private String operationId;

    @Column(length = 32)
    private String safeBindingDigestVersion;

    @Column(length = 128)
    private String safeBindingDigest;

    @Column(length = 16, nullable = false)
    private String ownershipMode;

    @Column(length = 16, nullable = false)
    private String canonicalPhase;

    @Column(length = 32)
    private String terminalOutcome;

    @Column(length = 48)
    private String terminalSource;

    @Column(length = 32, nullable = false)
    private String availability;

    @Column(length = 48, nullable = false)
    private String conflictState;

    @Column(length = 24, nullable = false)
    private String cleanupState;

    @Column(nullable = false)
    private Long factCursor;

    @Column(length = 48, nullable = false)
    private String policyVersion;

    @Column(length = 96)
    private String writerGenerationId;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String snapshotJson;

    @Column(nullable = false)
    @Version
    private Long rowVersion;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
        if (rowVersion == null) rowVersion = 0L;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getSessionId() { return sessionId; }
    public String getPhysicalWorkerId() { return physicalWorkerId; }
    public String getStateGeneration() { return stateGeneration; }
    public String getInstanceEpoch() { return instanceEpoch; }
    public String getProviderTaskId() { return providerTaskId; }
    public String getDispatchId() { return dispatchId; }
    public String getOperationId() { return operationId; }
    public String getSafeBindingDigestVersion() { return safeBindingDigestVersion; }
    public String getSafeBindingDigest() { return safeBindingDigest; }
    public String getOwnershipMode() { return ownershipMode; }
    public String getCanonicalPhase() { return canonicalPhase; }
    public String getTerminalOutcome() { return terminalOutcome; }
    public String getTerminalSource() { return terminalSource; }
    public String getAvailability() { return availability; }
    public String getConflictState() { return conflictState; }
    public String getCleanupState() { return cleanupState; }
    public Long getFactCursor() { return factCursor; }
    public String getWriterGenerationId() { return writerGenerationId; }
    public String getSnapshotJson() { return snapshotJson; }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public void setSessionId(String value) { sessionId = value; }
    public void setPhysicalWorkerId(String value) { physicalWorkerId = value; }
    public void setStateGeneration(String value) { stateGeneration = value; }
    public void setInstanceEpoch(String value) { instanceEpoch = value; }
    public void setProviderTaskId(String value) { providerTaskId = value; }
    public void setDispatchId(String value) { dispatchId = value; }
    public void setOperationId(String value) { operationId = value; }
    public void setSafeBindingDigestVersion(String value) {
        safeBindingDigestVersion = value;
    }
    public void setSafeBindingDigest(String value) { safeBindingDigest = value; }

    public void setOwnershipMode(String ownershipMode) {
        this.ownershipMode = ownershipMode;
    }

    public void setCanonicalPhase(String canonicalPhase) {
        this.canonicalPhase = canonicalPhase;
    }

    public void setTerminalOutcome(String terminalOutcome) {
        this.terminalOutcome = terminalOutcome;
    }

    public void setTerminalSource(String terminalSource) {
        this.terminalSource = terminalSource;
    }

    public void setAvailability(String availability) {
        this.availability = availability;
    }

    public void setConflictState(String conflictState) {
        this.conflictState = conflictState;
    }

    public void setCleanupState(String cleanupState) {
        this.cleanupState = cleanupState;
    }

    public void setFactCursor(Long factCursor) {
        this.factCursor = factCursor;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public void setWriterGenerationId(String writerGenerationId) {
        this.writerGenerationId = writerGenerationId;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }
}
