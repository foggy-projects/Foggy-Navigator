package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "lifecycle_effect_outbox", indexes = {
        @Index(name = "uk_leo_idempotency", columnList = "idempotencyKey", unique = true),
        @Index(name = "idx_leo_state", columnList = "effectState")
})
public class LifecycleEffectOutboxEntity {

    @Id
    @Column(length = 96, nullable = false)
    private String effectId;

    @Column(length = 64, nullable = false)
    private String aggregateId;

    @Column(length = 32, nullable = false)
    private String aggregateType;

    @Column(length = 128)
    private String physicalWorkerId;

    @Column(length = 32)
    private String providerType;

    @Column(length = 128)
    private String providerTaskId;

    @Column(length = 96)
    private String dispatchId;

    @Column(length = 64)
    private String operationId;

    @Column(length = 128)
    private String bindingDigest;

    @Column(length = 16)
    private String ownershipMode;

    @Column(length = 128)
    private String stateGeneration;

    @Column(length = 128)
    private String instanceEpoch;

    @Column(length = 32)
    private String bindingDigestVersion;

    @Column(length = 64)
    private String effectClaim;

    @Column(length = 160)
    private String aggregateReferenceId;

    @Column(length = 96)
    private String writerGenerationId;

    @Column(length = 128)
    private String controllerInventoryDigest;

    @Column(length = 64, nullable = false)
    private String effectType;

    @Column(length = 32, nullable = false)
    private String effectClass;

    @Column(length = 24, nullable = false)
    private String effectState;

    @Column(length = 160, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(length = 96)
    private String proofId;

    @Column(length = 96)
    private String effectAuthorizationProofVersion;

    private LocalDateTime authorizedAt;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String contentFreePayloadJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Version
    @Column(nullable = false)
    private Long rowVersion;

    @PrePersist
    void create() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (rowVersion == null) rowVersion = 0L;
        if (aggregateType == null) aggregateType = "TASK";
    }

    public void setEffectId(String effectId) {
        this.effectId = effectId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public void setAggregateType(String value) { aggregateType = value; }
    public void setPhysicalWorkerId(String value) { physicalWorkerId = value; }
    public void setProviderType(String value) { providerType = value; }
    public void setProviderTaskId(String value) { providerTaskId = value; }
    public void setDispatchId(String value) { dispatchId = value; }
    public void setOperationId(String value) { operationId = value; }
    public void setBindingDigest(String value) { bindingDigest = value; }
    public void setOwnershipMode(String value) { ownershipMode = value; }
    public void setStateGeneration(String value) { stateGeneration = value; }
    public void setInstanceEpoch(String value) { instanceEpoch = value; }
    public void setBindingDigestVersion(String value) {
        bindingDigestVersion = value;
    }
    public void setEffectClaim(String value) { effectClaim = value; }
    public void setAggregateReferenceId(String value) { aggregateReferenceId = value; }
    public void setWriterGenerationId(String value) { writerGenerationId = value; }
    public void setControllerInventoryDigest(String value) {
        controllerInventoryDigest = value;
    }

    public void setEffectType(String effectType) {
        this.effectType = effectType;
    }

    public void setEffectClass(String effectClass) {
        this.effectClass = effectClass;
    }

    public void setEffectState(String effectState) {
        this.effectState = effectState;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public void setContentFreePayloadJson(String contentFreePayloadJson) {
        this.contentFreePayloadJson = contentFreePayloadJson;
    }

    public String getEffectId() { return effectId; }
    public String getAggregateId() { return aggregateId; }
    public String getAggregateType() { return aggregateType; }
    public String getPhysicalWorkerId() { return physicalWorkerId; }
    public String getProviderType() { return providerType; }
    public String getProviderTaskId() { return providerTaskId; }
    public String getDispatchId() { return dispatchId; }
    public String getOperationId() { return operationId; }
    public String getBindingDigest() { return bindingDigest; }
    public String getOwnershipMode() { return ownershipMode; }
    public String getStateGeneration() { return stateGeneration; }
    public String getInstanceEpoch() { return instanceEpoch; }
    public String getBindingDigestVersion() { return bindingDigestVersion; }
    public String getEffectClass() { return effectClass; }
    public String getEffectClaim() { return effectClaim; }
    public String getAggregateReferenceId() { return aggregateReferenceId; }
    public String getWriterGenerationId() { return writerGenerationId; }
    public String getControllerInventoryDigest() { return controllerInventoryDigest; }
    public String getEffectType() { return effectType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getContentFreePayloadJson() { return contentFreePayloadJson; }
    public String getEffectState() { return effectState; }
    public String getProofId() { return proofId; }
    public String getEffectAuthorizationProofVersion() {
        return effectAuthorizationProofVersion;
    }
    public void setProofId(String value) { proofId = value; }
    public void setEffectAuthorizationProofVersion(String value) {
        effectAuthorizationProofVersion = value;
    }
    public void setAuthorizedAt(LocalDateTime value) { authorizedAt = value; }
}
