package com.foggy.navigator.business.agent.model.entity;

import com.foggy.navigator.common.enums.ResourceOwnerType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "biz_worker_pool", indexes = {
        @Index(name = "idx_bwp_tenant", columnList = "tenantId"),
        @Index(name = "idx_bwp_backend", columnList = "workerBackend"),
        @Index(name = "idx_bwp_status", columnList = "status"),
        @Index(name = "idx_bwp_owner", columnList = "ownerType,ownerId")
})
public class BizWorkerPoolEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String poolId;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private ResourceOwnerType ownerType;

    @Column(length = 128, nullable = false)
    private String ownerId;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(length = 64, nullable = false)
    private String workerBackend;

    @Column(length = 64)
    private String routingPolicy;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 32, nullable = false)
    private String healthStatus;

    @Column(columnDefinition = "TEXT")
    private String capabilitiesJson;

    @Column(columnDefinition = "TEXT")
    private String labelsJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
