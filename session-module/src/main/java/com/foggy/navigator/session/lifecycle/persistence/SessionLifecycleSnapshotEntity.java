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
@Table(name = "session_lifecycle_snapshots")
public class SessionLifecycleSnapshotEntity {
    @Id
    @Column(length = 64)
    private String sessionId;
    @Column(length = 128)
    private String physicalWorkerId;
    @Column(length = 16, nullable = false)
    private String ownershipMode;
    @Column(length = 16, nullable = false)
    private String canonicalPhase;
    @Column(length = 64)
    private String foregroundTaskId;
    @Column(length = 24, nullable = false)
    private String foregroundLaneState;
    @Column(length = 32, nullable = false)
    private String availability;
    @Column(length = 48, nullable = false)
    private String conflictState;
    @Column(length = 96)
    private String writerGenerationId;
    @Version
    @Column(nullable = false)
    private Long rowVersion;
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
        if (rowVersion == null) rowVersion = 0L;
    }

    public String getSessionId() { return sessionId; }
    public String getForegroundTaskId() { return foregroundTaskId; }
    public String getForegroundLaneState() { return foregroundLaneState; }
    public String getOwnershipMode() { return ownershipMode; }
    public String getPhysicalWorkerId() { return physicalWorkerId; }
    public String getCanonicalPhase() { return canonicalPhase; }
    public void setSessionId(String value) { sessionId = value; }
    public void setPhysicalWorkerId(String value) { physicalWorkerId = value; }
    public void setOwnershipMode(String value) { ownershipMode = value; }
    public void setCanonicalPhase(String value) { canonicalPhase = value; }
    public void setForegroundTaskId(String value) { foregroundTaskId = value; }
    public void setForegroundLaneState(String value) { foregroundLaneState = value; }
    public void setAvailability(String value) { availability = value; }
    public void setConflictState(String value) { conflictState = value; }
    public void setWriterGenerationId(String value) { writerGenerationId = value; }
}
