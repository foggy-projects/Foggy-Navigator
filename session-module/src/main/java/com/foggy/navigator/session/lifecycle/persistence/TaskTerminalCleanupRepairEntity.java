package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Durable request fence for an explicit terminal cleanup repair. It is
 * separate from a terminal tombstone's original operation correlation.
 */
@Entity
@Table(name = "task_terminal_cleanup_repairs")
public class TaskTerminalCleanupRepairEntity {

    @Id
    @Column(length = 64, nullable = false)
    private String taskId;

    @Column(length = 96, nullable = false, unique = true)
    private String clientRequestId;

    @Column(nullable = false)
    private boolean repairAccepted;

    @Column(nullable = false)
    private boolean terminalTombstonePresent;

    @Column(nullable = false)
    private boolean cleanupComplete;

    @Column(length = 96, nullable = false)
    private String safeReasonCode;

    @Column(nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @PrePersist
    void create() {
        if (recordedAt == null) recordedAt = LocalDateTime.now();
    }

    public String getTaskId() { return taskId; }
    public String getClientRequestId() { return clientRequestId; }
    public boolean isRepairAccepted() { return repairAccepted; }
    public boolean isTerminalTombstonePresent() {
        return terminalTombstonePresent;
    }
    public boolean isCleanupComplete() { return cleanupComplete; }
    public String getSafeReasonCode() { return safeReasonCode; }

    public void setTaskId(String value) { taskId = value; }
    public void setClientRequestId(String value) { clientRequestId = value; }
    public void setRepairAccepted(boolean value) { repairAccepted = value; }
    public void setTerminalTombstonePresent(boolean value) {
        terminalTombstonePresent = value;
    }
    public void setCleanupComplete(boolean value) { cleanupComplete = value; }
    public void setSafeReasonCode(String value) { safeReasonCode = value; }
}
