package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "task_terminal_cleanup_plan")
public class TaskTerminalCleanupPlanEntity {

    @EmbeddedId
    private TaskTerminalCleanupPlanId id;

    @Column(length = 24, nullable = false)
    private String applicability;

    @Column(length = 96)
    private String notApplicableReason;

    @Column(length = 24, nullable = false)
    private String checkpointState;

    @Column(length = 96)
    private String checkpointFactId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void create() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void update() {
        updatedAt = LocalDateTime.now();
    }

    public void setId(TaskTerminalCleanupPlanId id) {
        this.id = id;
    }

    public void setApplicability(String applicability) {
        this.applicability = applicability;
    }

    public void setNotApplicableReason(String notApplicableReason) {
        this.notApplicableReason = notApplicableReason;
    }

    public void setCheckpointState(String checkpointState) {
        this.checkpointState = checkpointState;
    }

    public TaskTerminalCleanupPlanId getId() { return id; }
    public String getApplicability() { return applicability; }
    public String getCheckpointState() { return checkpointState; }
    public void setCheckpointFactId(String value) { checkpointFactId = value; }
}
