package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "lifecycle_writer_exclusivity_proofs")
public class LifecycleWriterProofEntity {
    @Id @Column(length = 96)
    private String proofId;
    @Column(length = 96, nullable = false)
    private String generationId;
    @Column(length = 128, nullable = false)
    private String controllerInventoryDigest;
    @Column(length = 128, nullable = false)
    private String holderInstanceId;
    @Column(nullable = false)
    private long proofVersion;
    @Column(length = 24, nullable = false)
    private String status;
    @Column(nullable = false)
    private LocalDateTime acquiredAt;
    @Column(nullable = false)
    private LocalDateTime lastVerifiedAt;
    @Column(nullable = false)
    private LocalDateTime expiresAt;
    @Version @Column(nullable = false)
    private Long rowVersion;

    public String getProofId() { return proofId; }
    public String getGenerationId() { return generationId; }
    public long getProofVersion() { return proofVersion; }
    public String getStatus() { return status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setProofId(String value) { proofId = value; }
    public void setGenerationId(String value) { generationId = value; }
    public void setControllerInventoryDigest(String value) { controllerInventoryDigest = value; }
    public void setHolderInstanceId(String value) { holderInstanceId = value; }
    public void setProofVersion(long value) { proofVersion = value; }
    public void setStatus(String value) { status = value; }
    public void setAcquiredAt(LocalDateTime value) { acquiredAt = value; }
    public void setLastVerifiedAt(LocalDateTime value) { lastVerifiedAt = value; }
    public void setExpiresAt(LocalDateTime value) { expiresAt = value; }
}
