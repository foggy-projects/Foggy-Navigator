package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Worker 注册信息基类 —— 所有 Agent Worker 的公共字段。
 * <p>
 * 现有 ClaudeWorkerEntity 继承此类，保留 Claude 特有列（SSH、CodeServer、codexConfig）。
 * 新 Agent 的 Worker Entity 只需继承此类 + 加 providerExt JSON 列即可。
 */
@Data
@MappedSuperclass
public abstract class BaseWorkerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String workerId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64)
    private String tenantId;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(length = 512, nullable = false)
    private String baseUrl;

    @Column(length = 512, nullable = false)
    private String authToken;

    @Column(length = 32)
    private String authMode;

    @Column(length = 32)
    private String status;

    @Column(length = 256)
    private String hostname;

    @Column(length = 32)
    private String workerVersion;

    private LocalDateTime lastHeartbeat;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "UNKNOWN";
        if (authMode == null) authMode = "SUBSCRIPTION";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
