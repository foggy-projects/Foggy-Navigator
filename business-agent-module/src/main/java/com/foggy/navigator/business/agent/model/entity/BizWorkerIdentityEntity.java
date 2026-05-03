package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "biz_worker_identity", indexes = {
        @Index(name = "idx_bwi_backend", columnList = "workerBackend"),
        @Index(name = "idx_bwi_status", columnList = "status")
})
public class BizWorkerIdentityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String workerId;

    @Column(length = 64, nullable = false)
    private String workerBackend;

    @Column(length = 512, nullable = false)
    private String baseUrl;

    @Column(length = 64)
    private String version;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 32, nullable = false)
    private String healthStatus;

    @Column(length = 128)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private LocalDateTime registeredAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        registeredAt = LocalDateTime.now();
        updatedAt = registeredAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
