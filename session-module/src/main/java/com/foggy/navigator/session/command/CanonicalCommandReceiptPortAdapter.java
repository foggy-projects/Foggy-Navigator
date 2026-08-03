package com.foggy.navigator.session.command;

import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.CanonicalCommandReceiptPort;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/** Delegates the neutral SPI to Navigator's sole durable receipt state authority. */
@Service
public final class CanonicalCommandReceiptPortAdapter implements CanonicalCommandReceiptPort {

    private final CommandOnceReceiptService delegate;

    public CanonicalCommandReceiptPortAdapter(CommandOnceReceiptService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public PrepareResult prepare(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
        CommandOnceReceiptService.PrepareResult result = delegate.prepare(envelope, decision);
        return new PrepareResult(
                PrepareDisposition.valueOf(result.disposition().name()),
                snapshot(result.snapshot()));
    }

    @Override
    public EffectPermit beginEffect(
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
        CommandOnceReceiptService.EffectPermit permit = delegate.beginEffect(envelope, decision);
        return new EffectPermit(
                BeginEffectDisposition.valueOf(permit.disposition().name()),
                snapshot(permit.snapshot()));
    }

    @Override
    public ReceiptSnapshot recordResult(
            String clientRequestId,
            String effectAttemptId,
            String opaqueResultReference,
            String safeCode) {
        return snapshot(delegate.recordResult(
                clientRequestId, effectAttemptId, opaqueResultReference, safeCode));
    }

    @Override
    public ReceiptSnapshot markAmbiguous(
            String clientRequestId,
            String effectAttemptId,
            String safeCode) {
        return snapshot(delegate.markAmbiguous(clientRequestId, effectAttemptId, safeCode));
    }

    @Override
    public Optional<ReceiptSnapshot> find(String clientRequestId) {
        return delegate.find(clientRequestId).map(CanonicalCommandReceiptPortAdapter::snapshot);
    }

    private static ReceiptSnapshot snapshot(CommandOnceReceiptService.ReceiptSnapshot source) {
        return new ReceiptSnapshot(
                source.receiptId(),
                source.clientRequestId(),
                ReceiptState.valueOf(source.state().name()),
                source.effectAttemptId(),
                source.opaqueResultReference(),
                source.safeCode(),
                source.initialAuthorizationDecisionId(),
                source.initialAuthorizationIssuedAt(),
                source.initialAuthorizationNotBefore(),
                source.initialAuthorizationExpiresAt(),
                source.preparedAt(),
                source.effectStartedAt(),
                source.resultRecordedAt(),
                source.ambiguousAt(),
                source.rowVersion());
    }
}
