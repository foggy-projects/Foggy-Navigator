package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent worker pool authorization binding.
 *
 * <p>WorkerPool visibility decides what a principal can see. AgentWorkerBinding decides which
 * visible pool a specific Agent can use. CodingAgentEntity.workerId is the default worker pool
 * and is treated as an implicit binding.</p>
 */
@Data
@Entity
@Table(name = "coding_agent_workers", indexes = {
    @Index(name = "idx_caw_agent_id", columnList = "tenantId,agentId"),
    @Index(name = "idx_caw_worker_pool_id", columnList = "tenantId,workerPoolId")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_caw_tenant_agent_worker", columnNames = {"tenantId", "agentId", "workerPoolId"})
})
public class AgentWorkerBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String tenantId;

    /** FK -> CodingAgentEntity.agentId */
    @Column(length = 64, nullable = false)
    private String agentId;

    /** FK -> BizWorkerPoolEntity.poolId */
    @Column(length = 64, nullable = false)
    private String workerPoolId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
