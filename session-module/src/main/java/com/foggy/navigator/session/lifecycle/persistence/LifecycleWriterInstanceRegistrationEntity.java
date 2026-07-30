package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
    @Column(nullable = false)
    private LocalDateTime lastHeartbeatAt;

    public void setInstanceId(String value) { instanceId = value; }
    public void setGenerationId(String value) { generationId = value; }
    public void setOwnerProtocol(int value) { ownerProtocol = value; }
    public void setTargetCommit(String value) { targetCommit = value; }
    public void setLastHeartbeatAt(LocalDateTime value) { lastHeartbeatAt = value; }
}
