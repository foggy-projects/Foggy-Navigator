package com.foggy.navigator.session.command;

import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.CanonicalCommandReceiptPort;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonicalCommandReceiptPortAdapterTest {

    private final CommandOnceReceiptService delegate = mock(CommandOnceReceiptService.class);
    private final CanonicalCommandReceiptPort adapter =
            new CanonicalCommandReceiptPortAdapter(delegate);

    @Test
    void prepareMapsEveryDispositionAndReceiptStateWithoutLosingSnapshotFields() {
        CanonicalCommandEnvelope envelope = mock(CanonicalCommandEnvelope.class);
        VerifiedCommandAuthorizationDecision decision =
                mock(VerifiedCommandAuthorizationDecision.class);
        CommandOnceReceiptService.PrepareDisposition[] dispositions =
                CommandOnceReceiptService.PrepareDisposition.values();
        CommandOnceReceiptService.ReceiptState[] states =
                CommandOnceReceiptService.ReceiptState.values();

        for (int index = 0; index < dispositions.length; index++) {
            CommandOnceReceiptService.ReceiptSnapshot source = snapshot(states[index]);
            when(delegate.prepare(envelope, decision)).thenReturn(
                    new CommandOnceReceiptService.PrepareResult(dispositions[index], source));

            CanonicalCommandReceiptPort.PrepareResult actual =
                    adapter.prepare(envelope, decision);

            assertEquals(dispositions[index].name(), actual.disposition().name());
            assertSnapshot(source, actual.snapshot());
        }

        CommandOnceReceiptService.ReceiptSnapshot ambiguous =
                snapshot(CommandOnceReceiptService.ReceiptState.AMBIGUOUS);
        when(delegate.prepare(envelope, decision)).thenReturn(
                new CommandOnceReceiptService.PrepareResult(
                        CommandOnceReceiptService.PrepareDisposition.EXACT_REPLAY,
                        ambiguous));
        assertSnapshot(ambiguous, adapter.prepare(envelope, decision).snapshot());
    }

    @Test
    void beginEffectMapsEveryDispositionAndOnlyPermittedAllowsProviderEffect() {
        CanonicalCommandEnvelope envelope = mock(CanonicalCommandEnvelope.class);
        VerifiedCommandAuthorizationDecision decision =
                mock(VerifiedCommandAuthorizationDecision.class);

        for (CommandOnceReceiptService.BeginEffectDisposition disposition
                : CommandOnceReceiptService.BeginEffectDisposition.values()) {
            CommandOnceReceiptService.EffectPermit source =
                    mock(CommandOnceReceiptService.EffectPermit.class);
            CommandOnceReceiptService.ReceiptSnapshot sourceSnapshot = snapshot(
                    disposition == CommandOnceReceiptService.BeginEffectDisposition.PERMITTED
                            ? CommandOnceReceiptService.ReceiptState.EFFECT_STARTED
                            : CommandOnceReceiptService.ReceiptState.valueOf(
                                    disposition == CommandOnceReceiptService.BeginEffectDisposition.ALREADY_STARTED
                                            ? "EFFECT_STARTED"
                                            : disposition.name()));
            when(source.disposition()).thenReturn(disposition);
            when(source.snapshot()).thenReturn(sourceSnapshot);
            when(delegate.beginEffect(envelope, decision)).thenReturn(source);

            CanonicalCommandReceiptPort.EffectPermit actual =
                    adapter.beginEffect(envelope, decision);

            assertEquals(disposition.name(), actual.disposition().name());
            assertEquals(
                    disposition == CommandOnceReceiptService.BeginEffectDisposition.PERMITTED,
                    actual.providerEffectPermitted());
            assertSnapshot(sourceSnapshot, actual.snapshot());
        }
    }

    @Test
    void recordAmbiguousAndFindDelegateExactArgumentsAndMapOptionalShape() {
        CommandOnceReceiptService.ReceiptSnapshot recorded =
                snapshot(CommandOnceReceiptService.ReceiptState.RESULT_RECORDED);
        CommandOnceReceiptService.ReceiptSnapshot ambiguous =
                snapshot(CommandOnceReceiptService.ReceiptState.AMBIGUOUS);
        when(delegate.recordResult("request", "attempt", "BUSINESS_TASK:task", "OK"))
                .thenReturn(recorded);
        when(delegate.markAmbiguous("request", "attempt", "FAILED"))
                .thenReturn(ambiguous);
        when(delegate.find("request")).thenReturn(Optional.of(recorded));
        when(delegate.find("missing")).thenReturn(Optional.empty());

        assertSnapshot(recorded, adapter.recordResult(
                "request", "attempt", "BUSINESS_TASK:task", "OK"));
        assertSnapshot(ambiguous, adapter.markAmbiguous("request", "attempt", "FAILED"));
        assertTrue(adapter.find("request").isPresent());
        assertSnapshot(recorded, adapter.find("request").orElseThrow());
        assertFalse(adapter.find("missing").isPresent());

        verify(delegate).recordResult("request", "attempt", "BUSINESS_TASK:task", "OK");
        verify(delegate).markAmbiguous("request", "attempt", "FAILED");
        verify(delegate).find("missing");
    }

    @Test
    void delegateFailureIsPropagatedWithoutFallbackOrRemapping() {
        CanonicalCommandEnvelope envelope = mock(CanonicalCommandEnvelope.class);
        VerifiedCommandAuthorizationDecision decision =
                mock(VerifiedCommandAuthorizationDecision.class);
        IllegalStateException failure = new IllegalStateException("COMMAND_RECEIPT_BINDING_CONFLICT");
        when(delegate.prepare(envelope, decision)).thenThrow(failure);

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> adapter.prepare(envelope, decision));

        assertSame(failure, actual);
    }

    private static CommandOnceReceiptService.ReceiptSnapshot snapshot(
            CommandOnceReceiptService.ReceiptState state) {
        Instant issuedAt = Instant.parse("2026-08-04T01:02:03Z");
        LocalDateTime preparedAt = LocalDateTime.parse("2026-08-04T01:02:04");
        return new CommandOnceReceiptService.ReceiptSnapshot(
                "receipt",
                "request",
                state,
                "attempt",
                "BUSINESS_TASK:task",
                "SAFE",
                "decision",
                issuedAt,
                issuedAt.plusSeconds(1),
                issuedAt.plusSeconds(60),
                preparedAt,
                preparedAt.plusSeconds(1),
                preparedAt.plusSeconds(2),
                preparedAt.plusSeconds(3),
                7L);
    }

    private static void assertSnapshot(
            CommandOnceReceiptService.ReceiptSnapshot expected,
            CanonicalCommandReceiptPort.ReceiptSnapshot actual) {
        assertEquals(expected.receiptId(), actual.receiptId());
        assertEquals(expected.clientRequestId(), actual.clientRequestId());
        assertEquals(expected.state().name(), actual.state().name());
        assertEquals(expected.effectAttemptId(), actual.effectAttemptId());
        assertEquals(expected.opaqueResultReference(), actual.opaqueResultReference());
        assertEquals(expected.safeCode(), actual.safeCode());
        assertEquals(expected.initialAuthorizationDecisionId(),
                actual.initialAuthorizationDecisionId());
        assertEquals(expected.initialAuthorizationIssuedAt(), actual.initialAuthorizationIssuedAt());
        assertEquals(expected.initialAuthorizationNotBefore(), actual.initialAuthorizationNotBefore());
        assertEquals(expected.initialAuthorizationExpiresAt(), actual.initialAuthorizationExpiresAt());
        assertEquals(expected.preparedAt(), actual.preparedAt());
        assertEquals(expected.effectStartedAt(), actual.effectStartedAt());
        assertEquals(expected.resultRecordedAt(), actual.resultRecordedAt());
        assertEquals(expected.ambiguousAt(), actual.ambiguousAt());
        assertEquals(expected.rowVersion(), actual.rowVersion());
    }
}
