package com.foggy.navigator.session.command.persistence;

import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Content-free durable receipt for one exact command request identity.
 *
 * <p>The binding and first authorization provenance are immutable. Current authorization must
 * still be verified by the canonical in-process authority; persisted metadata is audit evidence,
 * not an executable capability.</p>
 */
@Entity
@Table(
        name = "command_once_receipts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_cor_client_request",
                        columnNames = "client_request_id"),
                @UniqueConstraint(
                        name = "uk_cor_effect_attempt",
                        columnNames = "effect_attempt_id")
        })
public class CommandOnceReceiptEntity {

    public enum ReceiptState {
        PREPARED,
        EFFECT_STARTED,
        RESULT_RECORDED,
        AMBIGUOUS
    }

    @Id
    @Column(name = "receipt_id", length = 64, nullable = false, updatable = false)
    private String receiptId;

    @Column(name = "command_schema_version", length = 64, nullable = false, updatable = false)
    private String commandSchemaVersion;

    @Column(name = "command_kind", length = 32, nullable = false, updatable = false)
    private String commandKind;

    @Column(name = "command_ingress", length = 32, nullable = false, updatable = false)
    private String commandIngress;

    @Column(name = "client_surface", length = 128, nullable = false, updatable = false)
    private String clientSurface;

    @Column(name = "route_id", length = 256, nullable = false, updatable = false)
    private String routeId;

    @Column(name = "client_request_id", length = 256, nullable = false, updatable = false)
    private String clientRequestId;

    @Column(name = "idempotency_key", length = 256, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "correlation_id", length = 256, nullable = false, updatable = false)
    private String correlationId;

    @Column(name = "actor_kind", length = 32, nullable = false, updatable = false)
    private String actorKind;

    @Column(name = "principal_type", length = 64, updatable = false)
    private String principalType;

    @Column(name = "credential_lane", length = 64, updatable = false)
    private String credentialLane;

    @Column(name = "principal_fingerprint", length = 256, updatable = false)
    private String principalFingerprint;

    @Column(
            name = "server_process_authority_reference",
            length = 256,
            updatable = false)
    private String serverProcessAuthorityReference;

    @Column(name = "tenant_reference", length = 256, nullable = false, updatable = false)
    private String tenantReference;

    @Column(name = "owner_reference", length = 256, nullable = false, updatable = false)
    private String ownerReference;

    @Column(name = "client_app_reference", length = 256, updatable = false)
    private String clientAppReference;

    @Column(name = "upstream_reference", length = 256, updatable = false)
    private String upstreamReference;

    @Column(name = "target_kind", length = 32, nullable = false, updatable = false)
    private String targetKind;

    @Column(name = "target_id", length = 256, nullable = false, updatable = false)
    private String targetId;

    @Column(name = "logical_agent_id", length = 256, updatable = false)
    private String logicalAgentId;

    @Column(name = "provider_type", length = 256, updatable = false)
    private String providerType;

    @Column(name = "physical_worker_id", length = 256, updatable = false)
    private String physicalWorkerId;

    @Column(name = "model_config_id", length = 256, updatable = false)
    private String modelConfigId;

    @Column(name = "task_id", length = 256, updatable = false)
    private String taskId;

    @Column(name = "session_id", length = 256, updatable = false)
    private String sessionId;

    @Column(name = "action_id", length = 256, nullable = false, updatable = false)
    private String actionId;

    @Column(name = "effect_scope_reference", length = 256, nullable = false, updatable = false)
    private String effectScopeReference;

    @Column(
            name = "authorization_metadata_schema_version",
            length = 64,
            nullable = false,
            updatable = false)
    private String authorizationMetadataSchemaVersion;

    @Column(
            name = "authorization_decision_id",
            length = 256,
            nullable = false,
            updatable = false)
    private String authorizationDecisionId;

    @Column(
            name = "authorization_policy_version",
            length = 256,
            nullable = false,
            updatable = false)
    private String authorizationPolicyVersion;

    @Column(
            name = "authorization_correlation_id",
            length = 256,
            nullable = false,
            updatable = false)
    private String authorizationCorrelationId;

    @Column(
            name = "authorization_issued_at_epoch_second",
            nullable = false,
            updatable = false)
    private long authorizationIssuedAtEpochSecond;

    @Column(name = "authorization_issued_at_nano", nullable = false, updatable = false)
    private int authorizationIssuedAtNano;

    @Column(
            name = "authorization_not_before_epoch_second",
            nullable = false,
            updatable = false)
    private long authorizationNotBeforeEpochSecond;

    @Column(name = "authorization_not_before_nano", nullable = false, updatable = false)
    private int authorizationNotBeforeNano;

    @Column(
            name = "authorization_expires_at_epoch_second",
            nullable = false,
            updatable = false)
    private long authorizationExpiresAtEpochSecond;

    @Column(name = "authorization_expires_at_nano", nullable = false, updatable = false)
    private int authorizationExpiresAtNano;

    @Column(name = "binding_digest_version", length = 32, nullable = false, updatable = false)
    private String bindingDigestVersion;

    @Column(name = "binding_digest", length = 64, nullable = false, updatable = false)
    private String bindingDigest;

    @Column(
            name = "authorization_binding_digest_version",
            length = 32,
            nullable = false,
            updatable = false)
    private String authorizationBindingDigestVersion;

    @Column(
            name = "authorization_binding_digest",
            length = 64,
            nullable = false,
            updatable = false)
    private String authorizationBindingDigest;

    @Column(name = "receipt_state", length = 32, nullable = false)
    private String receiptState;

    @Column(name = "effect_attempt_id", length = 64)
    private String effectAttemptId;

    @Column(name = "opaque_result_reference", length = 320)
    private String opaqueResultReference;

    @Column(name = "safe_code", length = 128)
    private String safeCode;

    @Column(name = "prepared_at", nullable = false, updatable = false)
    private LocalDateTime preparedAt;

    @Column(name = "effect_started_at")
    private LocalDateTime effectStartedAt;

    @Column(name = "result_recorded_at")
    private LocalDateTime resultRecordedAt;

    @Column(name = "ambiguous_at")
    private LocalDateTime ambiguousAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected CommandOnceReceiptEntity() {
    }

    /** Creates the immutable PREPARED row from the already verified, content-free envelope. */
    public static CommandOnceReceiptEntity prepared(
            String receiptId,
            CanonicalCommandEnvelope envelope,
            String bindingDigestVersion,
            String bindingDigest,
            String authorizationBindingDigestVersion,
            String authorizationBindingDigest,
            LocalDateTime now) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        requireSha256(receiptId, "receiptId");
        requireReference(bindingDigestVersion, 32, "bindingDigestVersion");
        requireSha256(bindingDigest, "bindingDigest");
        requireReference(
                authorizationBindingDigestVersion,
                32,
                "authorizationBindingDigestVersion");
        requireSha256(authorizationBindingDigest, "authorizationBindingDigest");

        CanonicalCommandEnvelope.CommandBinding binding = envelope.binding();
        CanonicalCommandEnvelope.AuthorizationMetadata authorization =
                envelope.authorizationMetadata();
        CommandOnceReceiptEntity receipt = new CommandOnceReceiptEntity();
        receipt.receiptId = receiptId;
        receipt.commandSchemaVersion = envelope.schemaVersion();
        receipt.commandKind = binding.commandKind().name();
        receipt.commandIngress = binding.ingress().ingress().name();
        receipt.clientSurface = binding.ingress().clientSurface();
        receipt.routeId = binding.ingress().routeId();
        receipt.clientRequestId = binding.request().clientRequestId();
        receipt.idempotencyKey = binding.request().idempotencyKey();
        receipt.correlationId = binding.request().correlationId();
        receipt.actorKind = binding.actor().kind().name();
        receipt.principalType = name(binding.actor().principalType());
        receipt.credentialLane = name(binding.actor().lane());
        receipt.principalFingerprint = binding.actor().fingerprint();
        receipt.serverProcessAuthorityReference =
                binding.actor().serverProcessAuthorityReference();
        receipt.tenantReference = binding.ownership().tenantReference();
        receipt.ownerReference = binding.ownership().ownerReference();
        receipt.clientAppReference = binding.ownership().clientAppReference();
        receipt.upstreamReference = binding.ownership().upstreamReference();
        receipt.targetKind = binding.target().kind().name();
        receipt.targetId = binding.target().targetId();
        receipt.logicalAgentId = binding.target().logicalAgentId();
        receipt.providerType = binding.target().providerType();
        receipt.physicalWorkerId = binding.target().physicalWorkerId();
        receipt.modelConfigId = binding.target().modelConfigId();
        receipt.taskId = binding.target().taskId();
        receipt.sessionId = binding.target().sessionId();
        receipt.actionId = binding.effect().actionId();
        receipt.effectScopeReference = binding.effect().effectScopeReference();
        receipt.authorizationMetadataSchemaVersion = authorization.schemaVersion();
        receipt.authorizationDecisionId = authorization.decisionId();
        receipt.authorizationPolicyVersion = authorization.policyVersion();
        receipt.authorizationCorrelationId = authorization.correlationId();
        receipt.authorizationIssuedAtEpochSecond = authorization.issuedAt().getEpochSecond();
        receipt.authorizationIssuedAtNano = authorization.issuedAt().getNano();
        receipt.authorizationNotBeforeEpochSecond = authorization.notBefore().getEpochSecond();
        receipt.authorizationNotBeforeNano = authorization.notBefore().getNano();
        receipt.authorizationExpiresAtEpochSecond = authorization.expiresAt().getEpochSecond();
        receipt.authorizationExpiresAtNano = authorization.expiresAt().getNano();
        receipt.bindingDigestVersion = bindingDigestVersion;
        receipt.bindingDigest = bindingDigest;
        receipt.authorizationBindingDigestVersion = authorizationBindingDigestVersion;
        receipt.authorizationBindingDigest = authorizationBindingDigest;
        receipt.receiptState = ReceiptState.PREPARED.name();
        receipt.preparedAt = requireOperationalTime(now, "now");
        return receipt;
    }

    /** Consumes the only provider-effect permit represented by this receipt. */
    public void beginEffect(String effectAttemptId, LocalDateTime now) {
        requireState(ReceiptState.PREPARED);
        String validatedAttemptId = requireReference(
                effectAttemptId, 64, "effectAttemptId");
        LocalDateTime validatedStartedAt = requireNotBefore(
                requireOperationalTime(now, "now"), preparedAt, "effectStartedAt");
        this.effectAttemptId = validatedAttemptId;
        this.effectStartedAt = validatedStartedAt;
        this.receiptState = ReceiptState.EFFECT_STARTED.name();
    }

    /** Records the opaque, content-free result identity for the exact effect attempt. */
    public void recordResult(
            String expectedAttemptId,
            String opaqueResultReference,
            String safeCode,
            LocalDateTime now) {
        requireEffectAttempt(expectedAttemptId);
        String validatedResultReference = requireReference(
                opaqueResultReference, 320, "opaqueResultReference");
        String validatedSafeCode = requireReference(safeCode, 128, "safeCode");
        LocalDateTime validatedRecordedAt = requireNotBefore(
                requireOperationalTime(now, "now"), effectStartedAt, "resultRecordedAt");
        this.opaqueResultReference = validatedResultReference;
        this.safeCode = validatedSafeCode;
        this.resultRecordedAt = validatedRecordedAt;
        this.receiptState = ReceiptState.RESULT_RECORDED.name();
    }

    /** Closes an already consumed effect permit when its outcome cannot be proven. */
    public void markAmbiguous(
            String expectedAttemptId,
            String safeCode,
            LocalDateTime now) {
        requireEffectAttempt(expectedAttemptId);
        String validatedSafeCode = requireReference(safeCode, 128, "safeCode");
        LocalDateTime validatedAmbiguousAt = requireNotBefore(
                requireOperationalTime(now, "now"), effectStartedAt, "ambiguousAt");
        this.safeCode = validatedSafeCode;
        this.ambiguousAt = validatedAmbiguousAt;
        this.receiptState = ReceiptState.AMBIGUOUS.name();
    }

    private void requireEffectAttempt(String expectedAttemptId) {
        requireState(ReceiptState.EFFECT_STARTED);
        String expected = requireReference(expectedAttemptId, 64, "expectedAttemptId");
        if (!Objects.equals(effectAttemptId, expected)) {
            throw new IllegalStateException("effect attempt does not match receipt");
        }
    }

    private void requireState(ReceiptState expected) {
        if (!expected.name().equals(receiptState)) {
            throw new IllegalStateException(
                    "receipt state must be " + expected + " but was " + receiptState);
        }
    }

    private static LocalDateTime requireOperationalTime(LocalDateTime value, String field) {
        return Objects.requireNonNull(value, field + " must not be null")
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static LocalDateTime requireNotBefore(
            LocalDateTime value, LocalDateTime floor, String field) {
        if (value.isBefore(floor)) {
            throw new IllegalArgumentException(field + " must not precede the prior state time");
        }
        return value;
    }

    private static String requireReference(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return value;
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be canonical SHA-256 hex");
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    public String getReceiptId() { return receiptId; }
    public String getCommandSchemaVersion() { return commandSchemaVersion; }
    public String getCommandKind() { return commandKind; }
    public String getCommandIngress() { return commandIngress; }
    public String getClientSurface() { return clientSurface; }
    public String getRouteId() { return routeId; }
    public String getClientRequestId() { return clientRequestId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCorrelationId() { return correlationId; }
    public String getActorKind() { return actorKind; }
    public String getPrincipalType() { return principalType; }
    public String getCredentialLane() { return credentialLane; }
    public String getPrincipalFingerprint() { return principalFingerprint; }
    public String getServerProcessAuthorityReference() {
        return serverProcessAuthorityReference;
    }
    public String getTenantReference() { return tenantReference; }
    public String getOwnerReference() { return ownerReference; }
    public String getClientAppReference() { return clientAppReference; }
    public String getUpstreamReference() { return upstreamReference; }
    public String getTargetKind() { return targetKind; }
    public String getTargetId() { return targetId; }
    public String getLogicalAgentId() { return logicalAgentId; }
    public String getProviderType() { return providerType; }
    public String getPhysicalWorkerId() { return physicalWorkerId; }
    public String getModelConfigId() { return modelConfigId; }
    public String getTaskId() { return taskId; }
    public String getSessionId() { return sessionId; }
    public String getActionId() { return actionId; }
    public String getEffectScopeReference() { return effectScopeReference; }
    public String getAuthorizationMetadataSchemaVersion() {
        return authorizationMetadataSchemaVersion;
    }
    public String getAuthorizationDecisionId() { return authorizationDecisionId; }
    public String getAuthorizationPolicyVersion() { return authorizationPolicyVersion; }
    public String getAuthorizationCorrelationId() { return authorizationCorrelationId; }
    public long getAuthorizationIssuedAtEpochSecond() {
        return authorizationIssuedAtEpochSecond;
    }
    public int getAuthorizationIssuedAtNano() { return authorizationIssuedAtNano; }
    public long getAuthorizationNotBeforeEpochSecond() {
        return authorizationNotBeforeEpochSecond;
    }
    public int getAuthorizationNotBeforeNano() { return authorizationNotBeforeNano; }
    public long getAuthorizationExpiresAtEpochSecond() {
        return authorizationExpiresAtEpochSecond;
    }
    public int getAuthorizationExpiresAtNano() { return authorizationExpiresAtNano; }
    public Instant getAuthorizationIssuedAt() {
        return Instant.ofEpochSecond(
                authorizationIssuedAtEpochSecond, authorizationIssuedAtNano);
    }
    public Instant getAuthorizationNotBefore() {
        return Instant.ofEpochSecond(
                authorizationNotBeforeEpochSecond, authorizationNotBeforeNano);
    }
    public Instant getAuthorizationExpiresAt() {
        return Instant.ofEpochSecond(
                authorizationExpiresAtEpochSecond, authorizationExpiresAtNano);
    }
    public String getBindingDigestVersion() { return bindingDigestVersion; }
    public String getBindingDigest() { return bindingDigest; }
    public String getAuthorizationBindingDigestVersion() {
        return authorizationBindingDigestVersion;
    }
    public String getAuthorizationBindingDigest() { return authorizationBindingDigest; }
    public ReceiptState getState() { return ReceiptState.valueOf(receiptState); }
    public String getReceiptState() { return receiptState; }
    public String getEffectAttemptId() { return effectAttemptId; }
    public String getOpaqueResultReference() { return opaqueResultReference; }
    public String getSafeCode() { return safeCode; }
    public LocalDateTime getPreparedAt() { return preparedAt; }
    public LocalDateTime getEffectStartedAt() { return effectStartedAt; }
    public LocalDateTime getResultRecordedAt() { return resultRecordedAt; }
    public LocalDateTime getAmbiguousAt() { return ambiguousAt; }
    public long getRowVersion() { return rowVersion == null ? 0L : rowVersion; }
}
