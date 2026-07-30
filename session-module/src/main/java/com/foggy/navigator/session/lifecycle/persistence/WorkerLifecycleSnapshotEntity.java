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
@Table(name = "worker_lifecycle_snapshots")
public class WorkerLifecycleSnapshotEntity {
    @Id @Column(length = 128)
    private String physicalWorkerId;
    @Column(length = 16, nullable = false)
    private String ownershipMode;
    @Column(length = 128)
    private String stateGeneration;
    @Column(length = 128)
    private String instanceEpoch;
    @Column(length = 32, nullable = false)
    private String availability;
    @Column(length = 48, nullable = false)
    private String conflictState;
    @Column(nullable = false)
    private long factCursor;
    @Column(length = 48, nullable = false)
    private String policyVersion;
    @Column(length = 96)
    private String writerGenerationId;
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String snapshotJson;
    @Version @Column(nullable = false)
    private Long rowVersion;
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
        if (rowVersion == null) rowVersion = 0L;
    }
    public void setPhysicalWorkerId(String value) { physicalWorkerId = value; }
    public void setOwnershipMode(String value) { ownershipMode = value; }
    public void setStateGeneration(String value) { stateGeneration = value; }
    public void setInstanceEpoch(String value) { instanceEpoch = value; }
    public void setAvailability(String value) { availability = value; }
    public void setConflictState(String value) { conflictState = value; }
    public void setFactCursor(long value) { factCursor = value; }
    public void setPolicyVersion(String value) { policyVersion = value; }
    public void setWriterGenerationId(String value) { writerGenerationId = value; }
    public void setSnapshotJson(String value) { snapshotJson = value; }
}
