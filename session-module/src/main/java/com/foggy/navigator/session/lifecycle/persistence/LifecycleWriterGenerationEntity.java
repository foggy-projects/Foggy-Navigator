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
    @Column(length = 24, nullable = false)
    private String status;
    private LocalDateTime activatedAt;
    @Version @Column(nullable = false)
    private Long rowVersion;

    public String getGenerationId() { return generationId; }
    public String getStatus() { return status; }
    public int getMinimumOwnerProtocol() { return minimumOwnerProtocol; }
    public void setGenerationId(String value) { generationId = value; }
    public void setMinimumOwnerProtocol(int value) { minimumOwnerProtocol = value; }
    public void setTargetCommit(String value) { targetCommit = value; }
    public void setStatus(String value) { status = value; }
    public void setActivatedAt(LocalDateTime value) { activatedAt = value; }
}
