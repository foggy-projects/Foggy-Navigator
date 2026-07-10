package com.foggy.navigator.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Latest known state of a provider-native subtask within one Navigator task.
 *
 * <p>This is an execution projection only. A row does not represent a Navigator
 * Agent, Session, or Task.</p>
 */
@Data
@Entity
@Table(name = "native_subtask_states",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_native_subtask_task_child", columnNames = {"taskId", "subtaskId"})
        },
        indexes = {
                @Index(name = "idx_native_subtask_task", columnList = "taskId"),
                @Index(name = "idx_native_subtask_session", columnList = "sessionId"),
                @Index(name = "idx_native_subtask_status", columnList = "status")
        })
public class NativeSubtaskStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String taskId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 32, nullable = false)
    private String providerType;

    @Column(length = 128, nullable = false)
    private String subtaskId;

    @Column(length = 128)
    private String parentSubtaskId;

    @Column(nullable = false)
    private Integer depth;

    @Column(length = 255)
    private String label;

    @Column(length = 128)
    private String role;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 64)
    private String activity;

    @Column(length = 64)
    private String message;

    private Long durationMs;

    @Column(nullable = false)
    private Integer contractVersion;

    @Column(nullable = false)
    private Integer lastEventSeq;

    private Instant startedAt;

    private Instant eventUpdatedAt;

    private Instant completedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (eventUpdatedAt == null) {
            eventUpdatedAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
