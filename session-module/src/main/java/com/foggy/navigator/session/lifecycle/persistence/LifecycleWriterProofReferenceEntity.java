package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "lifecycle_writer_exclusivity_references")
public class LifecycleWriterProofReferenceEntity {
    @Id @Column(length = 160)
    private String referenceId;
    @Column(length = 96, nullable = false)
    private String proofId;
    @Column(length = 16, nullable = false)
    private String aggregateType;
    @Column(length = 128, nullable = false)
    private String aggregateId;
    @Column(nullable = false)
    private LocalDateTime acquiredAt;
    private LocalDateTime releasedAt;
    @Column(length = 96)
    private String releaseReason;

    public String getReferenceId() { return referenceId; }
    public LocalDateTime getReleasedAt() { return releasedAt; }
    public void setReferenceId(String value) { referenceId = value; }
    public void setProofId(String value) { proofId = value; }
    public void setAggregateType(String value) { aggregateType = value; }
    public void setAggregateId(String value) { aggregateId = value; }
    public void setAcquiredAt(LocalDateTime value) { acquiredAt = value; }
    public void setReleasedAt(LocalDateTime value) { releasedAt = value; }
    public void setReleaseReason(String value) { releaseReason = value; }
}
