package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "lifecycle_writer_instance_registrations")
public class LifecycleWriterInstanceRegistrationEntity {
    @Id @Column(length = 128)
    private String instanceId;
    @Column(length = 96, nullable = false)
    private String generationId;
    @Column(nullable = false)
    private int ownerProtocol;
    @Column(length = 64, nullable = false)
    private String targetCommit;
    @Column(length = 96)
    private String targetId;
    @Column(length = 96)
    private String runId;
    @Column(length = 128)
    private String controllerInventoryDigest;
    @Column(length = 24)
    private String status;
    private LocalDateTime registeredAt;
    @Column(nullable = false)
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime expiresAt;
    @Version
    private Long rowVersion;

    public String getInstanceId() { return instanceId; }
    public String getGenerationId() { return generationId; }
    public int getOwnerProtocol() { return ownerProtocol; }
    public String getTargetCommit() { return targetCommit; }
    public String getTargetId() { return targetId; }
    public String getRunId() { return runId; }
    public String getControllerInventoryDigest() {
        return controllerInventoryDigest;
    }
    public String getStatus() { return status; }
    public LocalDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setInstanceId(String value) { instanceId = value; }
    public void setGenerationId(String value) { generationId = value; }
    public void setOwnerProtocol(int value) { ownerProtocol = value; }
    public void setTargetCommit(String value) { targetCommit = value; }
    public void setTargetId(String value) { targetId = value; }
    public void setRunId(String value) { runId = value; }
    public void setControllerInventoryDigest(String value) {
        controllerInventoryDigest = value;
    }
    public void setStatus(String value) { status = value; }
    public void setRegisteredAt(LocalDateTime value) { registeredAt = value; }
    public void setLastHeartbeatAt(LocalDateTime value) { lastHeartbeatAt = value; }
    public void setExpiresAt(LocalDateTime value) { expiresAt = value; }
}
