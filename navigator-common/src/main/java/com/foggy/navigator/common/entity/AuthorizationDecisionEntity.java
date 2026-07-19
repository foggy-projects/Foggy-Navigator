package com.foggy.navigator.common.entity;

import com.foggy.navigator.common.authorization.AuthorizationDecisionAuditDraft;
import com.foggy.navigator.common.authorization.DeploymentIdentity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Append-only, redacted canonical decision/diff audit fact.
 *
 * <p>This is durable application evidence, not a claim of tamper-proof
 * storage. It has no raw credential, token, account or request-body column.</p>
 */
@Getter
@Entity
@Table(name = "authorization_decision", indexes = {
        @Index(name = "idx_auth_decision_correlation", columnList = "correlation_id"),
        @Index(name = "idx_auth_decision_principal", columnList = "principal_type,principal_fingerprint"),
        @Index(name = "idx_auth_decision_credential", columnList = "credential_lane,credential_fingerprint"),
        @Index(name = "idx_auth_decision_action", columnList = "action_id"),
        @Index(name = "idx_auth_decision_target", columnList = "target_type,target_fingerprint"),
        @Index(name = "idx_auth_decision_result_reason", columnList = "decision,reason_code"),
        @Index(name = "idx_auth_decision_evaluated_at", columnList = "evaluated_at")
})
public class AuthorizationDecisionEntity {

    @Id
    @Column(name = "decision_id", length = 64, updatable = false)
    private String decisionId;

    @Column(name = "schema_version", length = 64, nullable = false, updatable = false)
    private String schemaVersion;

    @Column(name = "policy_version", length = 64, nullable = false, updatable = false)
    private String policyVersion;

    @Column(name = "action_catalog_version", length = 64, nullable = false, updatable = false)
    private String actionCatalogVersion;

    @Column(name = "server_build", length = 128, nullable = false, updatable = false)
    private String serverBuild;

    /** Resolved only from the server-owned deployment identity provider. */
    @Column(name = "navigator_instance_id", length = 64, nullable = false, updatable = false)
    private String navigatorInstanceId;

    /** Resolved only from the server-owned deployment identity provider. */
    @Column(name = "environment_profile", length = 32, nullable = false, updatable = false)
    private String environmentProfile;

    @Column(name = "correlation_id", length = 128, nullable = false, updatable = false)
    private String correlationId;

    /** SHADOW or future canonical evaluation mode; it is audit metadata only. */
    @Column(name = "evaluation_mode", length = 32, nullable = false, updatable = false)
    private String evaluationMode;

    @Column(name = "principal_type", length = 64, nullable = false, updatable = false)
    private String principalType;

    /** Stable redacted digest, never an account identifier or raw principal material. */
    @Column(name = "principal_fingerprint", length = 64, updatable = false)
    private String principalFingerprint;

    @Column(name = "credential_lane", length = 64, updatable = false)
    private String credentialLane;

    /** Stable redacted digest, never a credential or token value. */
    @Column(name = "credential_fingerprint", length = 64, updatable = false)
    private String credentialFingerprint;

    @Column(name = "action_id", length = 160, nullable = false, updatable = false)
    private String actionId;

    @Column(name = "target_type", length = 160, updatable = false)
    private String targetType;

    /** Stable redacted digest, never a raw target identifier. */
    @Column(name = "target_fingerprint", length = 64, updatable = false)
    private String targetFingerprint;

    @Column(name = "route_id", length = 192, nullable = false, updatable = false)
    private String routeId;

    @Column(name = "request_digest", length = 64, updatable = false)
    private String requestDigest;

    @Column(name = "impact_digest", length = 64, updatable = false)
    private String impactDigest;

    /** ALLOW, DENY or UNKNOWN canonical decision value. */
    @Column(name = "decision", length = 16, nullable = false, updatable = false)
    private String decision;

    @Column(name = "reason_code", length = 160, nullable = false, updatable = false)
    private String reasonCode;

    @Column(name = "legacy_decision", length = 16, updatable = false)
    private String legacyDecision;

    @Column(name = "legacy_reason_code", length = 160, updatable = false)
    private String legacyReasonCode;

    /** MATCH, LEGACY_ALLOW_CANONICAL_DENY, or another stable diff classification. */
    @Column(name = "diff_code", length = 96, updatable = false)
    private String diffCode;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private LocalDateTime evaluatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected AuthorizationDecisionEntity() {
        // JPA only. Audit records are constructed through fromAuditDraft.
    }

    /**
     * The only supported application constructor. It accepts an already
     * redacted draft and supplies the deployment identity from the server-side
     * provider, so request context cannot override identity or persist raw
     * material.
     */
    public static AuthorizationDecisionEntity fromAuditDraft(AuthorizationDecisionAuditDraft draft,
                                                              DeploymentIdentity deploymentIdentity) {
        if (draft == null || deploymentIdentity == null) {
            throw new IllegalArgumentException("audit draft and deployment identity are required");
        }
        draft.validate();
        AuthorizationDecisionEntity entity = new AuthorizationDecisionEntity();
        entity.decisionId = draft.decisionId();
        entity.schemaVersion = draft.schemaVersion();
        entity.policyVersion = draft.policyVersion();
        entity.actionCatalogVersion = draft.actionCatalogVersion();
        entity.serverBuild = draft.serverBuild();
        entity.navigatorInstanceId = deploymentIdentity.navigatorInstanceId();
        entity.environmentProfile = deploymentIdentity.environmentProfile();
        entity.correlationId = draft.correlationId();
        entity.evaluationMode = draft.evaluationMode();
        entity.principalType = draft.principalType();
        entity.principalFingerprint = draft.principalFingerprint();
        entity.credentialLane = draft.credentialLane();
        entity.credentialFingerprint = draft.credentialFingerprint();
        entity.actionId = draft.actionId();
        entity.targetType = draft.targetType();
        entity.targetFingerprint = draft.targetFingerprint();
        entity.routeId = draft.routeId();
        entity.requestDigest = draft.requestDigest();
        entity.impactDigest = draft.impactDigest();
        entity.decision = draft.decision();
        entity.reasonCode = draft.reasonCode();
        entity.legacyDecision = draft.legacyDecision();
        entity.legacyReasonCode = draft.legacyReasonCode();
        entity.diffCode = draft.diffCode();
        entity.evaluatedAt = LocalDateTime.ofInstant(draft.evaluatedAt(), java.time.ZoneOffset.UTC);
        return entity;
    }

    @PrePersist
    protected void onCreate() {
        validateAppendOnlyPayload();
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
    }

    /** Application-level guard; database hardening is a later production concern. */
    @PreUpdate
    protected void rejectUpdate() {
        throw new IllegalStateException("authorization_decision is append-only");
    }

    /** Application-level guard; database hardening is a later production concern. */
    @PreRemove
    protected void rejectRemove() {
        throw new IllegalStateException("authorization_decision is append-only");
    }

    private void validateAppendOnlyPayload() {
        if (decisionId == null || decisionId.isBlank() || schemaVersion == null || policyVersion == null
                || actionCatalogVersion == null || serverBuild == null || navigatorInstanceId == null
                || environmentProfile == null || correlationId == null || evaluationMode == null
                || principalType == null || actionId == null || routeId == null || decision == null
                || reasonCode == null || evaluatedAt == null) {
            throw new IllegalStateException("authorization_decision requires a complete redacted audit payload");
        }
    }
}
