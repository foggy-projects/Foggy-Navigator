package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofReferenceRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.SessionLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofReferenceEntity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

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
        when(proofs.findForUpdate("proof-1")).thenReturn(Optional.of(expired));
        when(refs.findForUpdate("reference-1")).thenReturn(Optional.of(reference()));
        when(outbox.findForUpdate("effect-1")).thenReturn(Optional.of(effect));

        WriterExclusivityProofService service =
                service(proofs, refs, outbox);
        var authorization = service.authorizeEffect(command(), NOW);
        assertThat(authorization.providerCallAuthorized()).isFalse();
        assertThat(authorization.safeReasonCode())
                .isEqualTo("LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        verify(outbox, never()).save(effect);
    }

    @Test
    void effectStartedBeforeProofLossIsNeverAuthorizedAgain() {
        LifecycleWriterProofRepository proofs = mock(LifecycleWriterProofRepository.class);
        LifecycleWriterProofReferenceRepository refs =
                mock(LifecycleWriterProofReferenceRepository.class);
        LifecycleEffectOutboxRepository outbox = mock(LifecycleEffectOutboxRepository.class);
        LifecycleEffectOutboxEntity effect = effect("CLAIMED");
        when(outbox.findForUpdate("effect-1")).thenReturn(Optional.of(effect));
        when(proofs.findForUpdate("proof-1"))
                .thenReturn(Optional.of(proof("ACTIVE", NOW.plusMinutes(1))));
        when(refs.findForUpdate("reference-1")).thenReturn(Optional.of(reference()));
        WriterExclusivityProofService service =
                service(proofs, refs, outbox);

        assertThat(service.authorizeEffect(command(), NOW)
                .providerCallAuthorized()).isTrue();
        assertThat(service.authorizeEffect(command(), NOW.plusSeconds(1))
                .alreadyStarted()).isTrue();
        verify(proofs, org.mockito.Mockito.times(2)).findForUpdate("proof-1");
    }

    private LifecycleEffectOutboxEntity effect(String state) {
        LifecycleEffectOutboxEntity effect = new LifecycleEffectOutboxEntity();
        effect.setEffectId("effect-1");
        effect.setAggregateType("TASK");
        effect.setAggregateId("task-1");
        effect.setEffectClass("EXTERNAL_PROVIDER_ONCE");
        effect.setEffectState(state);
        effect.setAggregateReferenceId("reference-1");
        effect.setWriterGenerationId("generation-1");
        effect.setControllerInventoryDigest("inventory-1");
        effect.setEffectClaim("TASK_CREATE_PROVIDER_CALL");
        return effect;
    }

    private LifecycleWriterProofEntity proof(String status, LocalDateTime expires) {
        LifecycleWriterProofEntity proof = new LifecycleWriterProofEntity();
        proof.setProofId("proof-1");
        proof.setGenerationId("generation-1");
        proof.setControllerInventoryDigest("inventory-1");
        proof.setProofVersion(7);
        proof.setStatus(status);
        proof.setExpiresAt(expires);
        return proof;
    }

    private LifecycleWriterProofReferenceEntity reference() {
        LifecycleWriterProofReferenceEntity reference =
                new LifecycleWriterProofReferenceEntity();
        reference.setReferenceId("reference-1");
        reference.setProofId("proof-1");
        reference.setAggregateType("TASK");
        reference.setAggregateId("task-1");
        reference.setAcquiredAt(NOW);
        return reference;
    }

    private WriterExclusivityProofService.EffectAuthorizationCommand command() {
        return new WriterExclusivityProofService.EffectAuthorizationCommand(
                "effect-1", "proof-1", "reference-1",
                ProofAggregateType.TASK, "task-1", "generation-1",
                "inventory-1", "TASK_CREATE_PROVIDER_CALL");
    }

    private WriterExclusivityProofService service(
            LifecycleWriterProofRepository proofs,
            LifecycleWriterProofReferenceRepository refs,
            LifecycleEffectOutboxRepository outbox) {
        return new WriterExclusivityProofService(
                proofs, refs, outbox,
                mock(TaskLifecycleSnapshotRepository.class),
                mock(SessionLifecycleSnapshotRepository.class),
                mock(com.foggy.navigator.session.lifecycle.repository
                        .WorkerLifecycleSnapshotRepository.class),
                mock(PlatformTransactionManager.class));
    }
}
