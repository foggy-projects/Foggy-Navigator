package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "business_task_scoped_token", indexes = {
        @Index(name = "idx_biz_token_task", columnList = "taskId")
})
public class BusinessTaskScopedTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long rowVersion;

    @Column(length = 64, nullable = false, unique = true)
    private String tokenId;

    @Column(length = 128, nullable = false)
    private String tokenHash;

    @Column(length = 64, nullable = false)
    private String taskId;

    @Column(length = 64)
    private String workerTaskId;

    @Column(length = 64)
    private String workerSessionId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String clientAppId;

    @Column(length = 128)
    private String upstreamUserId;

    @Column(length = 64, nullable = false)
    private String navigatorEffectiveUserId;

    @Column(length = 128)
    private String skillId;

    @Column(length = 64, nullable = false)
    private String workerPoolId;

    @Column(length = 64, nullable = false)
    private String modelConfigId;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(nullable = false)
    private Integer tokenVersion;

    @Column(nullable = false)
    private Integer generation;

    @Column(length = 64, nullable = false)
    private String audience;

    @Column(length = 64, nullable = false)
    private String identityAssurance;

    @Lob
    @Column(nullable = false)
    private String functionScopeJson;

    @Column(length = 128)
    private String workerId;

    @Column(length = 128)
    private String workerLeaseId;

    @Column(nullable = false)
    private LocalDateTime issuedAt;

    private LocalDateTime revokedAt;

    @Column(length = 128)
    private String revokedBy;

    @Column(length = 512)
    private String revokeReason;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

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
