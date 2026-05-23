package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "upstream_bootstrap_audit", indexes = {
        @Index(name = "idx_uba_request", columnList = "requestId"),
        @Index(name = "idx_uba_event", columnList = "eventType")
})
public class UpstreamBootstrapAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String auditId;

    @Column(length = 64, nullable = false)
    private String requestId;

    @Column(length = 16)
    private String requestCodeSuffix;

    @Column(length = 32, nullable = false)
    private String eventType;

    @Column(length = 32, nullable = false)
    private String requestStatus;

    @Column(length = 32, nullable = false)
    private String actorType;

    @Column(length = 128)
    private String actorId;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
