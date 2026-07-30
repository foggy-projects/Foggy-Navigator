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
