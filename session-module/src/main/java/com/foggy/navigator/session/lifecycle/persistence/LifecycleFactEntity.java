package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "lifecycle_facts", indexes = {
        @Index(name = "idx_lf_aggregate_cursor",
                columnList = "aggregateType,aggregateId,sourceSequence"),
        @Index(name = "idx_lf_task", columnList = "taskId"),
        @Index(name = "uk_lf_idempotency", columnList = "idempotencyKey", unique = true)
})
public class LifecycleFactEntity {

    @Id
    @Column(length = 96, nullable = false, updatable = false)
    private String factId;

    @Column(length = 96, nullable = false, updatable = false)
    private String factType;

    @Column(nullable = false, updatable = false)
    private Integer schemaVersion;

    @Column(length = 32, nullable = false, updatable = false)
    private String aggregateType;

    @Column(length = 128, nullable = false, updatable = false)
    private String aggregateId;

    @Column(length = 64, updatable = false)
    private String taskId;

    @Column(length = 64, updatable = false)
    private String sessionId;

    @Column(length = 64, updatable = false)
    private String operationId;

    @Column(length = 128, updatable = false)
    private String physicalWorkerId;

    @Column(length = 128, updatable = false)
    private String stateGeneration;

    @Column(length = 128, updatable = false)
    private String instanceEpoch;

    @Column(length = 128, updatable = false)
    private String providerTaskId;

    @Column(length = 96, updatable = false)
    private String dispatchId;

    @Column(length = 32, updatable = false)
    private String safeBindingDigestVersion;

    @Column(length = 128, updatable = false)
    private String safeBindingDigest;

    @Column(length = 16, nullable = false, updatable = false)
    private String ownershipMode;

    @Column(nullable = false, updatable = false)
    private Long sourceSequence;

    @Column(length = 160, nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(length = 96, updatable = false)
    private String safeReasonCode;

    @Column(columnDefinition = "TEXT", nullable = false, updatable = false)
    private String contentFreePayloadJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @PrePersist
    void onCreate() {
        if (recordedAt == null) recordedAt = LocalDateTime.now();
        if (schemaVersion == null) schemaVersion = 1;
    }

    public String getFactId() {
        return factId;
    }

    public String getContentFreePayloadJson() {
        return contentFreePayloadJson;
    }

    public void setFactId(String factId) {
        this.factId = factId;
    }

    public void setFactType(String factType) {
        this.factType = factType;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public void setPhysicalWorkerId(String value) { physicalWorkerId = value; }
    public void setStateGeneration(String value) { stateGeneration = value; }
    public void setInstanceEpoch(String value) { instanceEpoch = value; }
    public void setProviderTaskId(String value) { providerTaskId = value; }
    public void setDispatchId(String value) { dispatchId = value; }
    public void setSafeBindingDigestVersion(String value) {
        safeBindingDigestVersion = value;
    }
    public void setSafeBindingDigest(String value) { safeBindingDigest = value; }

    public String getFactType() { return factType; }
    public String getTaskId() { return taskId; }
    public String getSessionId() { return sessionId; }
    public String getOperationId() { return operationId; }
    public String getDispatchId() { return dispatchId; }
    public String getOwnershipMode() { return ownershipMode; }
    public Long getSourceSequence() { return sourceSequence; }

    public void setOwnershipMode(String ownershipMode) {
        this.ownershipMode = ownershipMode;
    }

    public void setSourceSequence(Long sourceSequence) {
        this.sourceSequence = sourceSequence;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public void setSafeReasonCode(String safeReasonCode) {
        this.safeReasonCode = safeReasonCode;
    }

    public void setContentFreePayloadJson(String contentFreePayloadJson) {
        this.contentFreePayloadJson = contentFreePayloadJson;
    }
}
