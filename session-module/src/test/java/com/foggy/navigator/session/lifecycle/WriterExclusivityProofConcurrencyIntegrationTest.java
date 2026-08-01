package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofReferenceEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofReferenceRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofRepository;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.LocalDateTime;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(classes = {
        TaskLifecycleOwnerVerticalIntegrationTest.Config.class,
        WriterExclusivityProofService.class,
        WorkerLifecycleReconciliationCommitService.class
})
class WriterExclusivityProofConcurrencyIntegrationTest {
    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-07-31T12:00:00");

    @org.springframework.beans.factory.annotation.Autowired
    WriterExclusivityProofService service;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleWriterProofRepository proofs;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleWriterProofReferenceRepository references;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleEffectOutboxRepository outbox;
    @org.springframework.beans.factory.annotation.Autowired
    WorkerLifecycleSnapshotRepository workerSnapshots;
    @org.springframework.beans.factory.annotation.Autowired
    WorkerLifecycleReconciliationCommitService reconciliation;

    @BeforeEach
    void setUp() {
        outbox.deleteAll();
        references.deleteAll();
        proofs.deleteAll();
        LifecycleWriterProofEntity proof = new LifecycleWriterProofEntity();
        proof.setProofId("proof-1");
        proof.setGenerationId("generation-1");
        proof.setControllerInventoryDigest("inventory-1");
        proof.setHolderInstanceId("instance-1");
        proof.setProofVersion(7);
        proof.setStatus("ACTIVE");
        proof.setAcquiredAt(NOW.minusMinutes(1));
        proof.setLastVerifiedAt(NOW);
        proof.setExpiresAt(NOW.plusMinutes(5));
        proofs.saveAndFlush(proof);

        LifecycleWriterProofReferenceEntity reference =
                new LifecycleWriterProofReferenceEntity();
        reference.setReferenceId("reference-1");
        reference.setProofId("proof-1");
        reference.setAggregateType("TASK");
        reference.setAggregateId("task-proof-1");
        reference.setAcquiredAt(NOW);
        references.saveAndFlush(reference);

        LifecycleEffectOutboxEntity effect = new LifecycleEffectOutboxEntity();
        effect.setEffectId("effect-1");
        effect.setAggregateType("TASK");
        effect.setAggregateId("task-proof-1");
        effect.setEffectType("TASK_CREATE");
        effect.setEffectClass("EXTERNAL_PROVIDER_ONCE");
        effect.setEffectState("CLAIMED");
        effect.setIdempotencyKey("effect-proof-fixture-1");
        effect.setAggregateReferenceId("reference-1");
        effect.setWriterGenerationId("generation-1");
        effect.setControllerInventoryDigest("inventory-1");
        effect.setEffectClaim("TASK_CREATE_PROVIDER_CALL");
        effect.setContentFreePayloadJson("{}");
        outbox.saveAndFlush(effect);
    }

    @Test
    void quarantineRecoveryUsesDurableFiftyReferenceBatches() {
        outbox.deleteAll();
        references.deleteAll();
        LifecycleWriterProofEntity proof = proofs.findById("proof-1")
                .orElseThrow();
        proof.setStatus("QUARANTINING");
        proofs.saveAndFlush(proof);

        var referenceBatch = new ArrayList<LifecycleWriterProofReferenceEntity>();
        for (int index = 0; index < 120; index++) {
            LifecycleWriterProofReferenceEntity reference =
                    new LifecycleWriterProofReferenceEntity();
            reference.setReferenceId("reference-%03d".formatted(index));
            reference.setProofId("proof-1");
            reference.setAggregateType("TASK");
            reference.setAggregateId("task-proof-%03d".formatted(index));
            reference.setAcquiredAt(NOW);
            referenceBatch.add(reference);
        }
        references.saveAllAndFlush(referenceBatch);

        service.resumeQuarantines();
        LifecycleWriterProofEntity afterFirst = proofs.findById("proof-1")
                .orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo("QUARANTINING");
        assertThat(afterFirst.getQuarantineCursor())
                .isEqualTo("reference-049");

        // A later scheduler invocation (including after process restart) resumes
        // strictly after the committed cursor; it never repeats the first batch.
        service.resumeQuarantines();
        LifecycleWriterProofEntity afterSecond = proofs.findById("proof-1")
                .orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo("QUARANTINING");
        assertThat(afterSecond.getQuarantineCursor())
                .isEqualTo("reference-099");

        service.resumeQuarantines();
        LifecycleWriterProofEntity completed = proofs.findById("proof-1")
                .orElseThrow();
        assertThat(completed.getStatus()).isEqualTo("QUARANTINED");
        assertThat(completed.getQuarantineCursor())
                .isEqualTo("reference-119");
    }

    @Test
    void laterOrdinaryCheckpointCannotClearCommittedWriterQuarantine() {
        String workerId = "worker-proof-1";
        WorkerLifecycleSnapshotEntity worker = new WorkerLifecycleSnapshotEntity();
        worker.setPhysicalWorkerId(workerId);
        worker.setOwnershipMode("ENFORCED");
        worker.setStateGeneration("state-proof-1");
        worker.setInstanceEpoch("epoch-proof-1");
        worker.setAvailability(LifecycleAvailability.READY.name());
        worker.setConflictState(LifecycleConflictState.NONE.name());
        worker.setFactCursor(0L);
        worker.setPolicyVersion("ARCH-001-MVP-A");
        worker.setSnapshotJson("{}");
        worker.setWriterGenerationId("generation-1");
        workerSnapshots.saveAndFlush(worker);

        LifecycleWriterProofReferenceEntity workerReference =
                new LifecycleWriterProofReferenceEntity();
        workerReference.setReferenceId("proof-1:WORKER:" + workerId);
        workerReference.setProofId("proof-1");
        workerReference.setAggregateType("WORKER");
        workerReference.setAggregateId(workerId);
        workerReference.setAcquiredAt(NOW);
        references.saveAndFlush(workerReference);

        service.quarantine("proof-1");
        WorkerLifecycleSnapshotEntity quarantined = workerSnapshots
                .findById(workerId).orElseThrow();
        assertThat(quarantined.getAvailability())
                .isEqualTo(LifecycleAvailability.AUTHORITY_QUARANTINED.name());
        assertThat(quarantined.getConflictState()).isEqualTo(
                LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST.name());

        WorkerLifecycleIdentity identity = new WorkerLifecycleIdentity(
                workerId, "state-proof-1", "epoch-proof-1");
        reconciliation.commit(new WorkerLifecycleSnapshot(
                identity, 0, 1, true, java.util.List.of(),
                java.util.List.of()), quarantined);

        WorkerLifecycleSnapshotEntity afterCheckpoint = workerSnapshots
                .findById(workerId).orElseThrow();
        assertThat(afterCheckpoint.getAvailability())
                .isEqualTo(LifecycleAvailability.AUTHORITY_QUARANTINED.name());
        assertThat(afterCheckpoint.getConflictState()).isEqualTo(
                LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST.name());
    }

}
