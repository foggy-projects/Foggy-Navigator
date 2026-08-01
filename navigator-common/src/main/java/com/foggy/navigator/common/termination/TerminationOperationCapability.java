package com.foggy.navigator.common.termination;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.foggy.navigator.common.entity.TerminationOperationEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-worker wire capability for an already persisted termination operation.
 * The HMAC is deliberately calculated over the base64url payload, not decoded
 * JSON, so every worker can validate the exact bytes it received.
 */
public record TerminationOperationCapability(String encodedOperation, String signature) {

    public static final String OPERATION_HEADER = "X-Navigator-Termination-Operation";
    public static final String SIGNATURE_HEADER = "X-Navigator-Termination-Signature";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(5);

    public static TerminationOperationCapability issue(TerminationOperationEntity operation,
                                                       String workerToken) {
        return issueAt(operation, workerToken, Instant.now());
    }

    /**
     * Recreates byte-identical capability claims for a persisted operation so
     * receipt admission and the later Worker command share one exact binding.
     */
    public static TerminationOperationCapability issueStable(
            TerminationOperationEntity operation,
            String workerToken) {
        if (operation == null || operation.getRequestedAt() == null) {
            throw new IllegalStateException(
                    "TERMINATION_OPERATION_REQUESTED_AT_REQUIRED");
        }
        return issueAt(operation, workerToken, persistedPrecision(
                operation.getRequestedAt())
                .atZone(java.time.ZoneId.systemDefault()).toInstant());
    }

    /**
     * The operation schema persists timestamps as MySQL DATETIME(6). Normalize
     * before signing so the just-inserted entity and a later database reload
     * produce byte-identical capability claims.
     */
    private static java.time.LocalDateTime persistedPrecision(
            java.time.LocalDateTime value) {
        return value.withNano((value.getNano() / 1_000) * 1_000);
    }

    private static TerminationOperationCapability issueAt(
            TerminationOperationEntity operation,
            String workerToken,
            Instant issuedAt) {
        if (operation == null) throw new IllegalArgumentException("termination operation is required");
        if (workerToken == null || workerToken.isBlank()) {
            throw new IllegalStateException("TERMINATION_WORKER_TOKEN_REQUIRED");
        }
        if (operation.getProviderTaskId() == null || operation.getProviderTaskId().isBlank()) {
            throw new IllegalStateException("TERMINATION_PROVIDER_TASK_ID_REQUIRED");
        }
        if (operation.getWorkerId() == null || operation.getWorkerId().isBlank()) {
            throw new IllegalStateException("TERMINATION_WORKER_ID_REQUIRED");
        }
        Instant expiry = issuedAt.plus(MAX_LIFETIME);
        if (operation.getExpiresAt() != null) {
            Instant operationExpiry = operation.getExpiresAt()
                    .atZone(java.time.ZoneId.systemDefault()).toInstant();
            if (operationExpiry.isBefore(expiry)) expiry = operationExpiry;
        }
        if (!expiry.isAfter(issuedAt)) {
            throw new IllegalStateException("TERMINATION_OPERATION_EXPIRED");
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("schema_version", operation.getSchemaVersion() == null ? 1 : operation.getSchemaVersion());
        claims.put("operation_id", operation.getOperationId());
        claims.put("task_id", operation.getProviderTaskId());
        // Do not rely on an installation accidentally using a unique shared
        // secret: a Worker must be able to reject a validly signed capability
        // that was minted for another registered Worker.
        claims.put("worker_id", operation.getWorkerId());
        claims.put("kind", operation.getKind());
        claims.put("origin", operation.getOrigin());
        claims.put("actor_id", operation.getActorId());
        claims.put("actor_type", operation.getActorType());
        claims.put("authorization_decision_id", operation.getAuthorizationDecisionId());
        claims.put("reason_code", operation.getReasonCode());
        claims.put("correlation_id", operation.getCorrelationId());
        if (operation.getExpectedPid() != null) claims.put("expected_pid", operation.getExpectedPid());
        // Bind an explicit PID operation to an immutable process snapshot
        // (for example `claude-cli:<pid>:<started_at>`).  Do not emit a
        // placeholder: absence means the persisted operation was not bound
        // and Workers must fail closed for a manual PID action.
        if (operation.getExpectedProcessIdentity() != null
                && !operation.getExpectedProcessIdentity().isBlank()) {
            claims.put("expected_process_identity", operation.getExpectedProcessIdentity());
        }
        // Keep the wire contract language-neutral.  The Node Workers validate
        // these claims as ISO-8601 strings and Python accepts the same form;
        // epoch values here would make a valid Java-issued operation fail at
        // the Worker boundary before it reaches the explicit-action check.
        claims.put("issued_at", issuedAt.toString());
        claims.put("expires_at", expiry.toString());

        try {
            String payload = OBJECT_MAPPER.writeValueAsString(claims);
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            return new TerminationOperationCapability(encoded, hmac(encoded, workerToken));
        } catch (Exception e) {
            throw new IllegalStateException("TERMINATION_CAPABILITY_ENCODE_FAILED", e);
        }
    }

    private static String hmac(String encodedPayload, String workerToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(workerToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("TERMINATION_CAPABILITY_SIGN_FAILED", e);
        }
    }
}
