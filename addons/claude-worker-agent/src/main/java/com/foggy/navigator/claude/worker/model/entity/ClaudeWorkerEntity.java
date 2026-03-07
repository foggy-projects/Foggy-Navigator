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

    /** Code Server 公网 URL */
    @Column(length = 512)
    private String codeServerPublicUrl;

    /** Code Server 内网 URL */
    @Column(length = 512)
    private String codeServerInternalUrl;

    /** Code Server 密码（加密存储） */
    @Column(columnDefinition = "TEXT")
    private String codeServerPassword;

    /** Code Server folder 路径前缀（用于 Windows→WSL 路径转换，如 /mnt/{drive}） */
    @Column(length = 256)
    private String codeServerFolderPrefix;

    /** SSH 用户名 */
    @Column(length = 128)
    private String sshUsername;

    /** SSH 端口，默认 22 */
    private Integer sshPort;

    /** SSH 密码（加密存储） */
    @Column(columnDefinition = "TEXT")
    private String sshPassword;

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
