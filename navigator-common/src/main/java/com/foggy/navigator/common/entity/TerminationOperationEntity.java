package com.foggy.navigator.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable, provider-neutral evidence for an explicitly authorized CLI
 * termination request.  This is deliberately distinct from task status: an
 * accepted remote cancel is not proof that the CLI has exited.
 */
@Data
@Entity
@Table(name = "termination_operations", indexes = {
        @Index(name = "idx_to_task_id", columnList = "taskId"),
        @Index(name = "idx_to_provider_task_id", columnList = "providerTaskId"),
        @Index(name = "idx_to_session_id", columnList = "sessionId"),
        @Index(name = "idx_to_owner_scope", columnList = "ownerUserId,tenantId"),
        @Index(name = "idx_to_worker_id", columnList = "workerId"),
        @Index(name = "idx_to_status", columnList = "status"),
        @Index(name = "idx_to_expires_at", columnList = "expiresAt")
})
public class TerminationOperationEntity {

    @Id
    @Column(length = 64)
    private String operationId;

    @Column(nullable = false)
    private Integer schemaVersion;

    /** Navigator task id, used for ownership/audit lookups. */
    @Column(length = 64, nullable = false)
    private String taskId;

    /** Worker task id carried in the signed capability route binding. */
    @Column(length = 128)
    private String providerTaskId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 64, nullable = false)
    private String ownerUserId;

    @Column(length = 64)
    private String tenantId;

    @Column(length = 32, nullable = false)
    private String providerType;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 32, nullable = false)
    private String kind;

    @Column(length = 32, nullable = false)
    private String origin;

    @Column(length = 64, nullable = false)
    private String actorId;

    @Column(length = 32, nullable = false)
    private String actorType;

    @Column(length = 128)
    private String authorizationDecisionId;

    @Column(length = 160, nullable = false)
    private String reasonCode;

    @Column(length = 128)
    private String correlationId;

    private Integer expectedPid;

    @Column(length = 160)
    private String expectedProcessIdentity;

    /** ACCEPTED | RUNNING | CANCEL_REQUESTED | REJECTED | COMPLETED | FAILED | ABORTED */
    @Column(length = 32, nullable = false)
    private String status;

    /** PENDING | ACKNOWLEDGED | UNCONFIRMED | REJECTED | OBSERVED */
    @Column(length = 32, nullable = false)
    private String dispatchState;

    /** Non-terminal lifecycle evidence, for example TERMINATION_UNCONFIRMED. */
    @Column(length = 160)
    private String attentionCode;

    @Column(length = 160)
    private String failureCode;

    private LocalDateTime requestedAt;

    private LocalDateTime dispatchedAt;

    private LocalDateTime observedAt;

    private LocalDateTime expiresAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (schemaVersion == null) schemaVersion = 1;
        if (status == null) status = "ACCEPTED";
        if (dispatchState == null) dispatchState = "PENDING";
        if (requestedAt == null) requestedAt = now;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
