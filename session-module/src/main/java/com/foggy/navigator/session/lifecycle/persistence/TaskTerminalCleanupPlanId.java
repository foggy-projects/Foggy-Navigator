package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class TaskTerminalCleanupPlanId implements Serializable {

    @Column(length = 64, nullable = false)
    private String taskId;

    @Column(length = 48, nullable = false)
    private String participant;

    protected TaskTerminalCleanupPlanId() {
    }

    public TaskTerminalCleanupPlanId(String taskId, String participant) {
        this.taskId = taskId;
        this.participant = participant;
    }

    public String getTaskId() { return taskId; }
    public String getParticipant() { return participant; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TaskTerminalCleanupPlanId that)) return false;
        return Objects.equals(taskId, that.taskId)
                && Objects.equals(participant, that.participant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, participant);
    }
}
