package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * Sanitized, short-lived evidence for a ClientApp runtime request chain.
 *
 * <p>This table deliberately stores no credential, token, header set, prompt,
 * runtime payload, model response, environment value, or exception stack.</p>
 */
@Data
@Entity
@Table(name = "runtime_request_audit", indexes = {
        @Index(name = "idx_runtime_audit_request", columnList = "client_request_id", unique = true),
        @Index(name = "idx_runtime_audit_scope_time",
                columnList = "tenant_id,upstream_system_id,client_app_id,received_at"),
        @Index(name = "idx_runtime_audit_expiry", columnList = "expires_at")
})
public class RuntimeRequestAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_request_id", nullable = false, updatable = false, length = 36)
    private String clientRequestId;

    @Column(name = "operation", nullable = false, length = 32)
    private String operation;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "upstream_system_id", nullable = false, length = 128)
    private String upstreamSystemId;

    @Column(name = "client_app_id", nullable = false, length = 128)
    private String clientAppId;

    @Column(name = "credential_id", nullable = false, length = 128)
    private String credentialId;

    @Column(name = "agent_code", length = 255)
    private String agentCode;

    @Column(name = "upstream_user_id", length = 255)
    private String upstreamUserId;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "terminal")
    private Boolean terminal;

    @Column(name = "result", length = 128)
    private String result;

    @Column(name = "sanitized_error_code", length = 128)
    private String sanitizedErrorCode;

    @Column(name = "safe_error_summary", length = 255)
    private String safeErrorSummary;

    @Column(name = "http_request_received")
    private Boolean httpRequestReceived;

    @Column(name = "runtime_token_request_received")
    private Boolean runtimeTokenRequestReceived;

    @Column(name = "runtime_token_issued")
    private Boolean runtimeTokenIssued;

    @Column(name = "safe_smoke_request_received")
    private Boolean safeSmokeRequestReceived;

    @Column(name = "synthetic_evidence_created")
    private Boolean syntheticEvidenceCreated;

    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "physical_worker_id", length = 128)
    private String physicalWorkerId;

    @Column(name = "model_config_id", length = 128)
    private String modelConfigId;

    @Column(name = "model_variant", length = 255)
    private String modelVariant;

    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "effective_tool_count")
    private Integer effectiveToolCount;

    @Column(name = "requested_tool_count")
    private Integer requestedToolCount;

    @Column(name = "tool_scope_kind", length = 128)
    private String toolScopeKind;

    @Column(name = "tool_scope_source", length = 128)
    private String toolScopeSource;

    @Column(name = "effective_function_count")
    private Integer effectiveFunctionCount;

    @Column(name = "requested_function_count")
    private Integer requestedFunctionCount;

    @Column(name = "function_scope_source", length = 128)
    private String functionScopeSource;

    @Column(name = "task_token_function_scope_empty")
    private Boolean taskTokenFunctionScopeEmpty;

    @Column(name = "task_token_status", length = 64)
    private String taskTokenStatus;

    @Column(name = "runtime_dispatched")
    private Boolean runtimeDispatched;

    @Column(name = "model_dispatched")
    private Boolean modelDispatched;

    @Column(name = "business_function_dispatched")
    private Boolean businessFunctionDispatched;

    @Column(name = "dispatch_count")
    private Integer dispatchCount;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "recovery_count")
    private Integer recoveryCount;

    @PrePersist
    protected void onCreate() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }
}
