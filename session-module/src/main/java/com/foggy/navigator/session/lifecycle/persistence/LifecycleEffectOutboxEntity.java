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
    }

    public void setEffectId(String effectId) {
        this.effectId = effectId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
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
