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
@Table(name = "lifecycle_activation_targets")
public class LifecycleActivationTargetEntity {
    @Id @Column(length = 96)
    private String targetId;
    @Column(length = 96, nullable = false, unique = true)
    private String runId;
    @Column(length = 48, nullable = false)
    private String targetClass;
    @Column(length = 32, nullable = false)
    private String providerEvidenceLane;
    @Column(length = 32, nullable = false)
    private String providerType;
    @Column(length = 64, nullable = false)
    private String tenantId;
    @Column(length = 64, nullable = false)
    private String userId;
    @Column(length = 128, nullable = false)
    private String physicalWorkerId;
    @Column(length = 64, nullable = false)
    private String modelConfigId;
    @Column(length = 128, nullable = false)
    private String model;
    @Column(length = 256)
    private String codexHomeKey;
    @Column(length = 64, nullable = false)
    private String promptSha256;
    @Column(length = 64, nullable = false)
    private String targetCommit;
    @Column(length = 64, nullable = false)
    private String candidatePatchSha256;
    @Column(nullable = false)
    private int ownerProtocol;
    @Column(length = 64, nullable = false)
    private String workerVersion;
    @Column(nullable = false)
    private int workerProtocol;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String requiredCapabilitiesJson;
    @Column(length = 128, nullable = false)
    private String manifestDigest;
    @Column(length = 128, nullable = false)
    private String controllerInventoryDigest;
    @Column(length = 96, nullable = false)
    private String generationId;
    @Column(length = 128, nullable = false)
    private String writerInstanceId;
    @Column(length = 96)
    private String proofId;
    @Column(length = 128)
    private String workerStateGeneration;
    @Column(length = 128)
    private String workerInstanceEpoch;
    @Column(length = 64)
    private String reservedSessionId;
    @Column(length = 64)
    private String reservedTaskId;
    private LocalDateTime reservedAt;
    @Column(length = 24, nullable = false)
    private String status;
    @Column(length = 96)
    private String safeReasonCode;
    private LocalDateTime lastObservedAt;
    private LocalDateTime destroyedAt;
    @Version @Column(nullable = false)
    private Long rowVersion;
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (rowVersion == null) rowVersion = 0L;
    }

    public String getTargetId() { return targetId; }
    public String getRunId() { return runId; }
    public String getTargetClass() { return targetClass; }
    public String getProviderEvidenceLane() { return providerEvidenceLane; }
    public String getProviderType() { return providerType; }
    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getPhysicalWorkerId() { return physicalWorkerId; }
    public String getModelConfigId() { return modelConfigId; }
    public String getModel() { return model; }
    public String getCodexHomeKey() { return codexHomeKey; }
    public String getPromptSha256() { return promptSha256; }
    public String getTargetCommit() { return targetCommit; }
    public String getCandidatePatchSha256() { return candidatePatchSha256; }
    public int getOwnerProtocol() { return ownerProtocol; }
    public String getWorkerVersion() { return workerVersion; }
    public int getWorkerProtocol() { return workerProtocol; }
    public String getRequiredCapabilitiesJson() { return requiredCapabilitiesJson; }
    public String getManifestDigest() { return manifestDigest; }
    public String getControllerInventoryDigest() { return controllerInventoryDigest; }
    public String getGenerationId() { return generationId; }
    public String getWriterInstanceId() { return writerInstanceId; }
    public String getProofId() { return proofId; }
    public String getWorkerStateGeneration() { return workerStateGeneration; }
    public String getWorkerInstanceEpoch() { return workerInstanceEpoch; }
    public String getReservedSessionId() { return reservedSessionId; }
    public String getReservedTaskId() { return reservedTaskId; }
    public String getStatus() { return status; }
    public String getSafeReasonCode() { return safeReasonCode; }
    public LocalDateTime getLastObservedAt() { return lastObservedAt; }
    public void setTargetId(String value) { targetId = value; }
    public void setRunId(String value) { runId = value; }
    public void setTargetClass(String value) { targetClass = value; }
    public void setProviderEvidenceLane(String value) { providerEvidenceLane = value; }
    public void setProviderType(String value) { providerType = value; }
    public void setTenantId(String value) { tenantId = value; }
    public void setUserId(String value) { userId = value; }
    public void setPhysicalWorkerId(String value) { physicalWorkerId = value; }
    public void setModelConfigId(String value) { modelConfigId = value; }
    public void setModel(String value) { model = value; }
    public void setCodexHomeKey(String value) { codexHomeKey = value; }
    public void setPromptSha256(String value) { promptSha256 = value; }
    public void setTargetCommit(String value) { targetCommit = value; }
    public void setCandidatePatchSha256(String value) { candidatePatchSha256 = value; }
    public void setOwnerProtocol(int value) { ownerProtocol = value; }
    public void setWorkerVersion(String value) { workerVersion = value; }
    public void setWorkerProtocol(int value) { workerProtocol = value; }
    public void setRequiredCapabilitiesJson(String value) { requiredCapabilitiesJson = value; }
    public void setManifestDigest(String value) { manifestDigest = value; }
    public void setControllerInventoryDigest(String value) { controllerInventoryDigest = value; }
    public void setGenerationId(String value) { generationId = value; }
    public void setWriterInstanceId(String value) { writerInstanceId = value; }
    public void setProofId(String value) { proofId = value; }
    public void setWorkerStateGeneration(String value) { workerStateGeneration = value; }
    public void setWorkerInstanceEpoch(String value) { workerInstanceEpoch = value; }
    public void setReservedSessionId(String value) { reservedSessionId = value; }
    public void setReservedTaskId(String value) { reservedTaskId = value; }
    public void setReservedAt(LocalDateTime value) { reservedAt = value; }
    public void setStatus(String value) { status = value; }
    public void setSafeReasonCode(String value) { safeReasonCode = value; }
    public void setLastObservedAt(LocalDateTime value) { lastObservedAt = value; }
    public void setDestroyedAt(LocalDateTime value) { destroyedAt = value; }
}
