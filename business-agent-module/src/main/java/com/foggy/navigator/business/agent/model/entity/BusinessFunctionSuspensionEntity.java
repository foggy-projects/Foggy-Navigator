package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "business_function_suspension")
public class BusinessFunctionSuspensionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "suspend_id", nullable = false, unique = true, length = 64)
    private String suspendId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "client_app_id", nullable = false, length = 64)
    private String clientAppId;

    @Column(name = "upstream_user_id", nullable = false, length = 128)
    private String upstreamUserId;

    @Column(name = "skill_id", nullable = false, length = 128)
    private String skillId;

    @Column(name = "function_id", nullable = false, length = 128)
    private String functionId;

    @Column(name = "version", nullable = false, length = 32)
    private String version;

    @Column(name = "input_json", columnDefinition = "TEXT")
    private String inputJson;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "approval_id", length = 64)
    private String approvalId;

    @Column(name = "approved_by", length = 128)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "comment", length = 512)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
