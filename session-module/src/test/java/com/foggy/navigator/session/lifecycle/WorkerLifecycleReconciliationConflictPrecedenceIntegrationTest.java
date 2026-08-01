package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofReferenceEntity;
import com.foggy.navigator.session.lifecycle.persistence.SessionLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofReferenceRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofRepository;
import com.foggy.navigator.session.lifecycle.repository.SessionLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.NormalizedLifecycleFact;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleReadiness;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(classes = {
        TaskLifecycleOwnerVerticalIntegrationTest.Config.class,
        WorkerLifecycleReconciliationCommitService.class,
        LifecycleIngressGate.class
})
class WorkerLifecycleReconciliationConflictPrecedenceIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    WriterExclusivityProofService proofs;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleWriterProofRepository proofRepository;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleWriterProofReferenceRepository references;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleEffectOutboxRepository outbox;
    @org.springframework.beans.factory.annotation.Autowired
    WorkerLifecycleSnapshotRepository workers;
    @org.springframework.beans.factory.annotation.Autowired
    SessionLifecycleSnapshotRepository sessions;
    @org.springframework.beans.factory.annotation.Autowired
    TaskLifecycleSnapshotRepository tasks;
    @org.springframework.beans.factory.annotation.Autowired
    WorkerLifecycleReconciliationCommitService commits;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleIngressGate ingressGate;
    @org.springframework.beans.factory.annotation.Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void clear() {
        outbox.deleteAll();
        references.deleteAll();
        tasks.deleteAll();
        sessions.deleteAll();
        workers.deleteAll();
        proofRepository.deleteAll();
    }

    @ParameterizedTest
    @EnumSource(value = SentinelReconcileState.class, names = {
            "WORKER_UNAVAILABLE", "IDENTITY_CHANGED", "LEASE_NOT_ACQUIRED"
    })
    void proofLossBlockedThenSuccessfulCheckpointRemainsFailClosed(
            SentinelReconcileState blockedState) {
        AuthorityIds ids = seedAuthority(blockedState.name().toLowerCase());
        proofs.quarantine(ids.proofId());

        SentinelReconcileResult blocked = sentinel(blockedState)
                .reconcile(ids.workerId(), SentinelTrigger.TIMER,
                        port(ids.workerId(), blockedState));
        assertThat(blocked.state()).isEqualTo(blockedState);
        assertWorkerConflict(ids.workerId(),
                LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST);

        SentinelReconcileResult recovered = sentinel(SentinelReconcileState.READY)
                .reconcile(ids.workerId(), SentinelTrigger.TIMER,
                        port(ids.workerId(), SentinelReconcileState.READY));
        assertThat(recovered.state()).isEqualTo(SentinelReconcileState.READY);
        assertWorkerConflict(ids.workerId(),
                LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST);
        assertAuthority(sessions.findById(ids.sessionId()).orElseThrow()
                .getAvailability(), sessions.findById(ids.sessionId())
                .orElseThrow().getConflictState(),
                LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST);
        assertAuthority(tasks.findById(ids.taskId()).orElseThrow()
                .getAvailability(), tasks.findById(ids.taskId())
                .orElseThrow().getConflictState(),
                LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST);
        assertThat(proofRepository.findById(ids.proofId()).orElseThrow()
                .getStatus()).isEqualTo("QUARANTINED");
        assertThat(references.countByProofIdAndReleasedAtIsNull(ids.proofId()))
                .isEqualTo(3);

        AtomicInteger providerEffects = new AtomicInteger();
        assertThatThrownBy(() -> {
            ingressGate.reserveBeforeEffect(ids.sessionId(), ids.workerId());
            providerEffects.incrementAndGet();
        }).hasMessage("WORKER_DEPENDENT_MUTATION_NOT_READY");
        assertThat(providerEffects).hasValue(0);
    }

    @Test
    void reconciliationObservationsFollowFrozenConflictPrecedence() {
        seedWorker("writer-state", LifecycleAvailability.AUTHORITY_QUARANTINED,
                LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST);
        assertThat(sentinel(SentinelReconcileState.STATE_GENERATION_RESET)
                .reconcile("worker-writer-state", SentinelTrigger.TIMER,
                        port("worker-writer-state",
                                SentinelReconcileState.STATE_GENERATION_RESET))
                .state()).isEqualTo(SentinelReconcileState.STATE_GENERATION_RESET);
        assertWorkerConflict("worker-writer-state",
                LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST);

        seedWorker("evidence-state", LifecycleAvailability.AUTHORITY_QUARANTINED,
                LifecycleConflictState.EVIDENCE_CONFLICT);
        sentinel(SentinelReconcileState.STATE_GENERATION_RESET)
                .reconcile("worker-evidence-state", SentinelTrigger.TIMER,
                        port("worker-evidence-state",
                                SentinelReconcileState.STATE_GENERATION_RESET));
        assertWorkerConflict("worker-evidence-state",
                LifecycleConflictState.WORKER_STATE_LOSS);

        seedWorker("state-offline", LifecycleAvailability.AUTHORITY_QUARANTINED,
                LifecycleConflictState.WORKER_STATE_LOSS);
        sentinel(SentinelReconcileState.WORKER_UNAVAILABLE)
                .reconcile("worker-state-offline", SentinelTrigger.TIMER,
                        port("worker-state-offline",
                                SentinelReconcileState.WORKER_UNAVAILABLE));
        assertWorkerConflict("worker-state-offline",
                LifecycleConflictState.WORKER_STATE_LOSS);

        seedWorker("none-offline", LifecycleAvailability.READY,
                LifecycleConflictState.NONE);
        sentinel(SentinelReconcileState.WORKER_UNAVAILABLE)
                .reconcile("worker-none-offline", SentinelTrigger.TIMER,
                        port("worker-none-offline",
                                SentinelReconcileState.WORKER_UNAVAILABLE));
        assertWorkerState("worker-none-offline",
                LifecycleAvailability.OFFLINE_FROZEN,
                LifecycleConflictState.NONE);

        seedWorker("none-ready", LifecycleAvailability.OFFLINE_FROZEN,
                LifecycleConflictState.NONE);
        sentinel(SentinelReconcileState.READY)
                .reconcile("worker-none-ready", SentinelTrigger.TIMER,
                        port("worker-none-ready", SentinelReconcileState.READY));
        assertWorkerState("worker-none-ready", LifecycleAvailability.READY,
                LifecycleConflictState.NONE);
    }

    private WorkerLifecycleSentinelService sentinel(
            SentinelReconcileState desiredState) {
        SentinelLeaseStore leases = (worker, holder, now, duration) ->
                desiredState == SentinelReconcileState.LEASE_NOT_ACQUIRED
                        ? Optional.empty()
                        : Optional.of(new SentinelLease(worker, holder, 1));
        return new WorkerLifecycleSentinelService(
                leases, workers, commits);
    }

    private WorkerLifecyclePort port(
            String workerId, SentinelReconcileState desiredState) {
        WorkerLifecycleIdentity current = new WorkerLifecycleIdentity(
                workerId, "generation-" + workerId,
                "epoch-" + workerId);
        WorkerLifecycleIdentity reset = new WorkerLifecycleIdentity(
                workerId, "reset-" + workerId,
                "epoch-" + workerId);
        return new WorkerLifecyclePort() {
            @Override
            public WorkerLifecycleReadiness probe(String ignored) {
                if (desiredState == SentinelReconcileState.WORKER_UNAVAILABLE) {
                    return new WorkerLifecycleReadiness(
                            false, current, Set.of(),
                            List.of("LIFECYCLE_WORKER_UNAVAILABLE"));
                }
                return new WorkerLifecycleReadiness(
                        true, desiredState == SentinelReconcileState
                                .STATE_GENERATION_RESET ? reset : current,
                        Set.of("INVENTORY_V1"), List.of());
            }

            @Override
            public WorkerLifecycleSnapshot inventory(
                    WorkerLifecycleIdentity expectedIdentity,
                    long afterSequence) {
                if (desiredState == SentinelReconcileState.IDENTITY_CHANGED) {
                    throw new IllegalStateException("FIXTURE_IDENTITY_CHANGED");
                }
                return snapshot(desiredState == SentinelReconcileState
                        .STATE_GENERATION_RESET ? reset : current);
            }

            @Override
            public WorkerLifecycleSnapshot events(
                    WorkerLifecycleIdentity expectedIdentity,
                    long afterSequence) {
                return snapshot(current);
            }

            @Override
            public long acknowledge(
                    WorkerLifecycleIdentity expectedIdentity,
                    long throughSequence) {
                return throughSequence;
            }
        };
    }

    private WorkerLifecycleSnapshot snapshot(WorkerLifecycleIdentity identity) {
        return new WorkerLifecycleSnapshot(
                identity, 0, 1, true, List.of(),
                List.<NormalizedLifecycleFact>of());
    }

    private AuthorityIds seedAuthority(String suffix) {
        String proofId = "proof-r5-" + suffix;
        String workerId = "worker-" + suffix;
        String sessionId = "session-" + suffix;
        String taskId = "task-" + suffix;
        seedProof(proofId);
        seedWorker(suffix, LifecycleAvailability.READY,
                LifecycleConflictState.NONE);

        SessionLifecycleSnapshotEntity session =
                new SessionLifecycleSnapshotEntity();
        session.setSessionId(sessionId);
        session.setPhysicalWorkerId(workerId);
        session.setOwnershipMode("ENFORCED");
        session.setCanonicalPhase("OPEN");
        session.setForegroundLaneState("FREE");
        session.setAvailability(LifecycleAvailability.READY.name());
        session.setConflictState(LifecycleConflictState.NONE.name());
        session.setWriterGenerationId("writer-r5");
        sessions.saveAndFlush(session);

        TaskLifecycleSnapshotEntity task = new TaskLifecycleSnapshotEntity();
        task.setTaskId(taskId);
        task.setSessionId(sessionId);
        task.setPhysicalWorkerId(workerId);
        task.setStateGeneration("generation-" + workerId);
        task.setInstanceEpoch("epoch-" + workerId);
        task.setProviderTaskId("provider-" + suffix);
        task.setOwnershipMode("ENFORCED");
        task.setCanonicalPhase("OPEN");
        task.setAvailability(LifecycleAvailability.READY.name());
        task.setConflictState(LifecycleConflictState.NONE.name());
        task.setCleanupState("NOT_REQUIRED");
        task.setFactCursor(0L);
        task.setPolicyVersion("ARCH-001-MVP-A");
        task.setWriterGenerationId("writer-r5");
        task.setSnapshotJson("{}");
        tasks.saveAndFlush(task);

        references.saveAllAndFlush(List.of(
                reference(proofId + ":00:WORKER", proofId,
                        "WORKER", workerId),
                reference(proofId + ":01:SESSION", proofId,
                        "SESSION", sessionId),
                reference(proofId + ":02:TASK", proofId,
                        "TASK", taskId)));
        return new AuthorityIds(proofId, workerId, sessionId, taskId);
    }

    private void seedWorker(
            String suffix,
            LifecycleAvailability availability,
            LifecycleConflictState conflict) {
        WorkerLifecycleSnapshotEntity worker =
                new WorkerLifecycleSnapshotEntity();
        worker.setPhysicalWorkerId("worker-" + suffix);
        worker.setOwnershipMode("ENFORCED");
        worker.setStateGeneration("generation-worker-" + suffix);
        worker.setInstanceEpoch("epoch-worker-" + suffix);
        worker.setAvailability(availability.name());
        worker.setConflictState(conflict.name());
        worker.setFactCursor(0L);
        worker.setPolicyVersion("ARCH-001-MVP-A");
        worker.setWriterGenerationId("writer-r5");
        worker.setSnapshotJson("{}");
        workers.saveAndFlush(worker);
    }

    private void seedProof(String proofId) {
        LocalDateTime now = LocalDateTime.parse("2026-07-31T20:00:00");
        LifecycleWriterProofEntity proof = new LifecycleWriterProofEntity();
        proof.setProofId(proofId);
        proof.setGenerationId("writer-r5");
        proof.setControllerInventoryDigest("inventory-r5");
        proof.setHolderInstanceId("holder-r5");
        proof.setProofVersion(1L);
        proof.setStatus("ACTIVE");
        proof.setAcquiredAt(now.minusMinutes(1));
        proof.setLastVerifiedAt(now);
        proof.setExpiresAt(now.plusMinutes(5));
        proofRepository.saveAndFlush(proof);
    }

    private LifecycleWriterProofReferenceEntity reference(
            String id, String proofId, String type, String aggregateId) {
        LifecycleWriterProofReferenceEntity reference =
                new LifecycleWriterProofReferenceEntity();
        reference.setReferenceId(id);
        reference.setProofId(proofId);
        reference.setAggregateType(type);
        reference.setAggregateId(aggregateId);
        reference.setAcquiredAt(LocalDateTime.parse("2026-07-31T20:00:00"));
        return reference;
    }

    private void assertWorkerConflict(
            String workerId, LifecycleConflictState conflict) {
        assertWorkerState(workerId,
                LifecycleAvailability.AUTHORITY_QUARANTINED, conflict);
    }

    private void assertWorkerState(
            String workerId,
            LifecycleAvailability availability,
            LifecycleConflictState conflict) {
        WorkerLifecycleSnapshotEntity worker = workers.findById(workerId)
                .orElseThrow();
        assertAuthority(worker.getAvailability(), worker.getConflictState(),
                conflict, availability);
    }

    private void assertAuthority(
            String availability,
            String conflict,
            LifecycleConflictState expectedConflict) {
        assertAuthority(availability, conflict, expectedConflict,
                LifecycleAvailability.AUTHORITY_QUARANTINED);
    }

    private void assertAuthority(
            String availability,
            String conflict,
            LifecycleConflictState expectedConflict,
            LifecycleAvailability expectedAvailability) {
        assertThat(availability).isEqualTo(expectedAvailability.name());
        assertThat(conflict).isEqualTo(expectedConflict.name());
    }

    private record AuthorityIds(
            String proofId, String workerId, String sessionId, String taskId) {
    }
}
