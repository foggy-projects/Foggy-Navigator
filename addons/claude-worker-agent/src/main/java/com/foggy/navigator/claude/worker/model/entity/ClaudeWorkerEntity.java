package com.foggy.navigator.claude.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Claude Worker 注册信息
 */
@Data
@Entity
@Table(name = "claude_workers", indexes = {
    @Index(name = "idx_cw_user_id", columnList = "userId")
})
public class ClaudeWorkerEntity {

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
        if (status == null) {
            status = "UNKNOWN";
        }
        if (authMode == null) {
            authMode = "SUBSCRIPTION";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
