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

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

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
