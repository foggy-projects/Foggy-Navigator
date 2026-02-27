package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型-Worker 访问关联表
 * 记录 RESTRICTED 模型允许哪些 Worker 使用
 */
@Data
@Entity
@Table(name = "model_worker_access", indexes = {
    @Index(name = "idx_mwa_model_config_id", columnList = "modelConfigId"),
    @Index(name = "idx_mwa_worker_id", columnList = "workerId")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_mwa_model_worker", columnNames = {"modelConfigId", "workerId"})
})
public class ModelWorkerAccessEntity {

    @Id
    @Column(length = 64)
    private String id;

    /**
     * 租户ID
     */
    @Column(length = 64, nullable = false)
    private String tenantId;

    /**
     * 引用的模型配置ID（llm_model_config.id）
     */
    @Column(length = 64, nullable = false)
    private String modelConfigId;

    /**
     * 引用的 Worker ID（claude_workers.worker_id）
     */
    @Column(length = 64, nullable = false)
    private String workerId;

    /**
     * 创建时间
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
