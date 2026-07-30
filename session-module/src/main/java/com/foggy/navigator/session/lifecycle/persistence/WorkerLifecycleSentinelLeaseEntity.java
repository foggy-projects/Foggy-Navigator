package com.foggy.navigator.session.lifecycle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "worker_lifecycle_sentinel_leases")
public class WorkerLifecycleSentinelLeaseEntity {
    @Id @Column(length = 128)
    private String physicalWorkerId;
    @Column(length = 128, nullable = false)
    private String holderInstanceId;
    @Column(nullable = false)
    private long fenceToken;
    @Column(nullable = false)
    private LocalDateTime expiresAt;
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist @PreUpdate
    void touch() { updatedAt = LocalDateTime.now(); }
    public String getPhysicalWorkerId() { return physicalWorkerId; }
    public String getHolderInstanceId() { return holderInstanceId; }
    public long getFenceToken() { return fenceToken; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setPhysicalWorkerId(String value) { physicalWorkerId = value; }
    public void setHolderInstanceId(String value) { holderInstanceId = value; }
    public void setFenceToken(long value) { fenceToken = value; }
    public void setExpiresAt(LocalDateTime value) { expiresAt = value; }
}
