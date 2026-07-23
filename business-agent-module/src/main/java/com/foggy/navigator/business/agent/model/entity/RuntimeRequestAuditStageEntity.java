package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "runtime_request_audit_stage", indexes = {
        @Index(name = "idx_runtime_audit_stage_request_time",
                columnList = "client_request_id,occurred_at")
})
public class RuntimeRequestAuditStageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_request_id", nullable = false, updatable = false, length = 36)
    private String clientRequestId;

    @Column(name = "stage", nullable = false, length = 64)
    private String stage;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "sanitized_error_code", length = 128)
    private String sanitizedErrorCode;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
