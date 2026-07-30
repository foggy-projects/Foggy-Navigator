package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofReferenceRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WriterExclusivityProofServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-07-30T12:00:00");

    @Test
    void proofLossBeforeAuthorizationPreventsProviderCall() {
        LifecycleWriterProofRepository proofs = mock(LifecycleWriterProofRepository.class);
        LifecycleWriterProofReferenceRepository refs =
                mock(LifecycleWriterProofReferenceRepository.class);
        LifecycleEffectOutboxRepository outbox = mock(LifecycleEffectOutboxRepository.class);
        LifecycleEffectOutboxEntity effect = effect("CLAIMED");
        LifecycleWriterProofEntity expired = proof("QUARANTINED", NOW.minusSeconds(1));
        when(outbox.findForUpdate("effect-1")).thenReturn(Optional.of(effect));
        when(proofs.findForUpdate("proof-1")).thenReturn(Optional.of(expired));

        WriterExclusivityProofService service =
                new WriterExclusivityProofService(proofs, refs, outbox);
        assertThatThrownBy(() -> service.authorizeEffect("effect-1", "proof-1", NOW))
                .hasMessage("LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        verify(outbox, never()).save(effect);
    }

    @Test
    void effectStartedBeforeProofLossIsNeverAuthorizedAgain() {
        LifecycleWriterProofRepository proofs = mock(LifecycleWriterProofRepository.class);
        LifecycleWriterProofReferenceRepository refs =
                mock(LifecycleWriterProofReferenceRepository.class);
        LifecycleEffectOutboxRepository outbox = mock(LifecycleEffectOutboxRepository.class);
        LifecycleEffectOutboxEntity effect = effect("PROPOSED");
        when(outbox.findForUpdate("effect-1")).thenReturn(Optional.of(effect));
        when(proofs.findForUpdate("proof-1"))
                .thenReturn(Optional.of(proof("ACTIVE", NOW.plusMinutes(1))));
        WriterExclusivityProofService service =
                new WriterExclusivityProofService(proofs, refs, outbox);

        assertThat(service.authorizeEffect("effect-1", "proof-1", NOW)
                .providerCallAuthorized()).isTrue();
        assertThat(service.authorizeEffect("effect-1", "proof-1", NOW.plusSeconds(1))
                .alreadyStarted()).isTrue();
        verify(proofs).findForUpdate("proof-1");
    }

    private LifecycleEffectOutboxEntity effect(String state) {
        LifecycleEffectOutboxEntity effect = new LifecycleEffectOutboxEntity();
        effect.setEffectId("effect-1");
        effect.setEffectState(state);
        return effect;
    }

    private LifecycleWriterProofEntity proof(String status, LocalDateTime expires) {
        LifecycleWriterProofEntity proof = new LifecycleWriterProofEntity();
        proof.setProofId("proof-1");
        proof.setGenerationId("generation-1");
        proof.setProofVersion(7);
        proof.setStatus(status);
        proof.setExpiresAt(expires);
        return proof;
    }
}
