package com.foggy.navigator.codex.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Codex Worker 注册实体（精简版，无 SSH/CodeServer）
 */
@Data
@Entity
@Table(name = "codex_workers", indexes = {
    @Index(name = "idx_cxw_user_id", columnList = "userId")
})
public class CodexWorkerEntity {

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

    /** Worker base URL, e.g. http://localhost:3032 */
    @Column(length = 512, nullable = false)
    private String baseUrl;

    /** Bearer token (encrypted via CredentialEncryptor) */
    @Column(length = 512)
    private String authToken;

    /** ONLINE | OFFLINE | UNKNOWN */
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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
