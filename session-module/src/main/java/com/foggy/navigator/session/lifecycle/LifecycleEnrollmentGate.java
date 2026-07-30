package com.foggy.navigator.session.lifecycle;

import java.time.LocalDateTime;
import java.util.Set;

public final class LifecycleEnrollmentGate {

    public static final String CANARY_PROVIDER = "codex-biz-worker";
    private static final Set<String> REQUIRED_CAPABILITIES = Set.of(
            "AUTHENTICATED_LIFECYCLE_V1",
            "FENCED_INVENTORY_V1",
            "DURABLE_LIFECYCLE_FACTS_V1",
            "MONOTONIC_ACK_V1",
            "EXACT_DISPATCH_DEDUPE_V1",
            "DURABLE_PROVIDER_TASK_ID_V1",
            "TERMINATION_ATOMIC_CAPABILITY_V1");

    public EnrollmentDecision evaluate(EnrollmentRequest request) {
        if (!request.repoOwnedFixture() && !request.activationEvidencePresent()) {
            return rejected(LifecycleSchemaReadiness.ACTIVATION_DISABLED);
        }
        if (!CANARY_PROVIDER.equals(request.providerType())
                || !request.exactAllowlistedTuple()) {
            return rejected("LIFECYCLE_CANARY_TUPLE_NOT_ALLOWLISTED");
        }
        if (!request.schemaReady()) return rejected("LIFECYCLE_SCHEMA_NOT_READY");
        if (!request.ownerProtocolMatched() || !request.targetCommitMatched()) {
            return rejected("LIFECYCLE_OWNER_BINARY_MISMATCH");
        }
        if (!request.receiptEnabled()) {
            return rejected("TERMINATION_REQUEST_RECEIPT_REQUIRED_FOR_CANARY");
        }
        if (!request.lifecycleCredentialConfigured()) {
            return rejected("LIFECYCLE_AUTH_NOT_CONFIGURED");
        }
        if (!request.identityMatched()) {
            return rejected("LIFECYCLE_IDENTITY_FENCE_REJECTED");
        }
        if (!request.capabilities().containsAll(REQUIRED_CAPABILITIES)) {
            return rejected("LIFECYCLE_WORKER_CAPABILITY_MISMATCH");
        }
        if (!request.proofActive()
                || request.proofExpiresAt() == null
                || !request.proofExpiresAt().isAfter(request.evaluationTime())) {
            return rejected("LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        }
        return new EnrollmentDecision(true, false, "ENFORCED_FIXTURE_READY");
    }

    private EnrollmentDecision rejected(String reason) {
        return new EnrollmentDecision(false, true, reason);
    }

    public record EnrollmentRequest(
            String providerType,
            boolean exactAllowlistedTuple,
            boolean repoOwnedFixture,
            boolean activationEvidencePresent,
            boolean schemaReady,
            boolean ownerProtocolMatched,
            boolean targetCommitMatched,
            boolean receiptEnabled,
            boolean lifecycleCredentialConfigured,
            boolean identityMatched,
            Set<String> capabilities,
            boolean proofActive,
            LocalDateTime proofExpiresAt,
            LocalDateTime evaluationTime) {
        public EnrollmentRequest {
            capabilities = Set.copyOf(capabilities);
        }
    }

    public record EnrollmentDecision(
            boolean enrolled, boolean failClosed, String safeReasonCode) {
    }
}
