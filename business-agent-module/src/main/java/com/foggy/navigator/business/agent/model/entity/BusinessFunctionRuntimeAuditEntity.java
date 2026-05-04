package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistent audit record for business function runtime events.
 * Does NOT store plain task-scoped tokens, adapterConfigJson, or manifestJson.
 */
@Data
@Entity
@Table(name = "business_function_runtime_audit")
public class BusinessFunctionRuntimeAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "audit_id", nullable = false, unique = true, length = 64)
    private String auditId;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "client_app_id", length = 128)
    private String clientAppId;

    @Column(name = "upstream_user_id", length = 128)
    private String upstreamUserId;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "worker_pool_id", length = 64)
    private String workerPoolId;

    @Column(name = "skill_id", length = 128)
    private String skillId;

    @Column(name = "function_id", length = 255)
    private String functionId;

    @Column(name = "function_version", length = 64)
    private String functionVersion;

    @Column(name = "suspend_id", length = 64)
    private String suspendId;

    /**
     * Event type: INVOKE_STARTED, INVOKE_SUCCESS, INVOKE_SUSPENDED,
     * INVOKE_FAILED, TOOL_MESSAGE, RESUME_REQUESTED, RESUME_DISPATCHED, RESUME_FAILED
     */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "input_hash", length = 128)
    private String inputHash;

    @Column(name = "output_hash", length = 128)
    private String outputHash;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
