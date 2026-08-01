package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "lifecycle_writer_generations")
public class LifecycleWriterGenerationEntity {
    @Id @Column(length = 96)
    private String generationId;
    @Column(nullable = false)
    private int minimumOwnerProtocol;
    @Column(length = 64, nullable = false)
    private String targetCommit;
    @Column(length = 96)
    private String targetId;
    @Column(length = 96)
    private String runId;
    @Column(length = 128)
    private String controllerInventoryDigest;
    @Column(length = 16, unique = true)
    private String activeSlot;
    @Column(length = 24, nullable = false)
    private String status;
    private LocalDateTime activatedAt;
    @Version @Column(nullable = false)
    private Long rowVersion;

    public String getGenerationId() { return generationId; }
    public String getStatus() { return status; }
    public int getMinimumOwnerProtocol() { return minimumOwnerProtocol; }
    public String getTargetCommit() { return targetCommit; }
    public String getTargetId() { return targetId; }
    public String getRunId() { return runId; }
    public String getControllerInventoryDigest() {
        return controllerInventoryDigest;
    }
    public String getActiveSlot() { return activeSlot; }
    public void setGenerationId(String value) { generationId = value; }
    public void setMinimumOwnerProtocol(int value) { minimumOwnerProtocol = value; }
    public void setTargetCommit(String value) { targetCommit = value; }
    public void setTargetId(String value) { targetId = value; }
    public void setRunId(String value) { runId = value; }
    public void setControllerInventoryDigest(String value) {
        controllerInventoryDigest = value;
    }
    public void setActiveSlot(String value) { activeSlot = value; }
    public void setStatus(String value) { status = value; }
    public void setActivatedAt(LocalDateTime value) { activatedAt = value; }
}
