package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "biz_worker_pool_member", uniqueConstraints = {
        @UniqueConstraint(name = "uk_biz_worker_pool_member", columnNames = {"poolId", "workerId"})
}, indexes = {
        @Index(name = "idx_bwpm_pool", columnList = "poolId"),
        @Index(name = "idx_bwpm_worker", columnList = "workerId")
})
public class BizWorkerPoolMemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String poolId;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 32, nullable = false)
    private String status;

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
