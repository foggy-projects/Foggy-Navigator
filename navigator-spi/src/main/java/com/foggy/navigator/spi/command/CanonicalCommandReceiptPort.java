package com.foggy.navigator.spi.command;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Provider-neutral access to Navigator's single durable command receipt authority.
 *
 * <p>The port transports content-free command identity and receipt state only. Implementations
 * must not execute a Provider callback or introduce a second receipt ledger.</p>
 */
public interface CanonicalCommandReceiptPort {

    PrepareResult prepare(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision);

    EffectPermit beginEffect(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision);

    ReceiptSnapshot recordResult(
            String clientRequestId,
            String effectAttemptId,
            String opaqueResultReference,
            String safeCode);

    ReceiptSnapshot markAmbiguous(
            String clientRequestId,
            String effectAttemptId,
            String safeCode);

    Optional<ReceiptSnapshot> find(String clientRequestId);

    enum PrepareDisposition {
        CREATED,
        EXACT_REPLAY,
        AUTHORIZATION_RENEWAL_ACCEPTED
    }

    enum BeginEffectDisposition {
        PERMITTED,
        ALREADY_STARTED,
        RESULT_RECORDED,
        AMBIGUOUS
    }

    enum ReceiptState {
        PREPARED,
        EFFECT_STARTED,
        RESULT_RECORDED,
        AMBIGUOUS
    }

    record ReceiptSnapshot(
            String receiptId,
            String clientRequestId,
            ReceiptState state,
            String effectAttemptId,
            String opaqueResultReference,
            String safeCode,
            String initialAuthorizationDecisionId,
            Instant initialAuthorizationIssuedAt,
            Instant initialAuthorizationNotBefore,
            Instant initialAuthorizationExpiresAt,
            LocalDateTime preparedAt,
            LocalDateTime effectStartedAt,
            LocalDateTime resultRecordedAt,
            LocalDateTime ambiguousAt,
            long rowVersion) {
    }

    record PrepareResult(
            PrepareDisposition disposition,
            ReceiptSnapshot snapshot) {
    }

    record EffectPermit(
            BeginEffectDisposition disposition,
            ReceiptSnapshot snapshot) {

        public boolean providerEffectPermitted() {
            return disposition == BeginEffectDisposition.PERMITTED;
        }
    }
}
