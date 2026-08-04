package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleCommandAuthorizationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(classes = {
        TaskLifecycleOwnerVerticalIntegrationTest.Config.class,
        TaskTerminationIntentRecorder.class,
        WriterExclusivityProofService.class,
        LifecycleEnrollmentService.class,
        WorkerLifecycleReconciliationCommitService.class,
        TaskTerminationIntentRecorderIntegrationTest.AuthorityClockConfig.class
})
class TaskTerminationIntentRecorderIntegrationTest {
    private static final LocalDateTime AUTHORITY_NOW =
            LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final String AUTHORIZATION_CLAIM_A = "a".repeat(64);
    private static final String AUTHORIZATION_CLAIM_B = "b".repeat(64);

    static class AuthorityClockConfig {
        @Bean
        LifecycleAuthorityClock lifecycleAuthorityClock() {
            return new LifecycleAuthorityClock() {
                @Override
                public LocalDateTime databaseNow() {
                    return AUTHORITY_NOW;
                }

                @Override
                public DatabaseIdentity databaseIdentity() {
                    return new DatabaseIdentity(
                            "H2", "test", "test", "localhost", 0);
                }
            };
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    RuntimeTerminationIntentPort recorder;
    @org.springframework.beans.factory.annotation.Autowired
    WorkerLifecycleCommandAuthorizationPort commandAuthorization;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleEffectOutboxRepository outbox;
    @org.springframework.beans.factory.annotation.Autowired
    com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository
            snapshots;
    @org.springframework.beans.factory.annotation.Autowired
    PlatformTransactionManager transactionManager;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleEnrollmentService enrollment;
    @org.springframework.beans.factory.annotation.Autowired
    WorkerLifecycleReconciliationCommitService reconciliation;
    @org.springframework.beans.factory.annotation.Autowired
    com.foggy.navigator.session.lifecycle.repository
            .LifecycleWriterProofRepository proofs;
    @org.springframework.beans.factory.annotation.Autowired
    com.foggy.navigator.session.lifecycle.repository
            .LifecycleWriterProofReferenceRepository references;
    @org.springframework.beans.factory.annotation.Autowired
    com.foggy.navigator.session.lifecycle.repository
            .WorkerLifecycleSnapshotRepository workers;
    @org.springframework.beans.factory.annotation.Autowired
    com.foggy.navigator.session.lifecycle.repository
            .SessionLifecycleSnapshotRepository sessions;
    @org.springframework.beans.factory.annotation.Autowired
    com.foggy.navigator.common.repository.SessionTaskRepository canonicalTasks;

    @BeforeEach
    void setUp() {
        outbox.deleteAll();
        references.deleteAll();
        proofs.deleteAll();
        snapshots.deleteAll();
        sessions.deleteAll();
        workers.deleteAll();
        canonicalTasks.deleteAll();
        enrollFixture();
    }

    @Test
    void committedPreparedDeliveryCanContinueAfterRestartAndNeverAuthorizeTwice() {
        RuntimeTerminationIntentPort.RuntimeTerminationDelivery prepared =
                new TransactionTemplate(transactionManager).execute(status ->
                        recorder.recordIntent(intent("request-recovery")));
        assertThat(prepared.effectState()).isEqualTo("PREPARED");
        assertThat(prepared.ownerUserId()).isEqualTo("owner-delivery");
        assertThat(prepared.tenantId()).isEqualTo("tenant-delivery");
        assertThat(prepared.authorizationBindingClaim()).isEqualTo(
                RuntimeTerminationIntentPort.RuntimeTerminationIntent
                        .LEGACY_AUTHORIZATION_BINDING_CLAIM);

        var first = transaction(() ->
                recorder.authorizeEffect("request-recovery"));
        var redelivery = recorder.authorizeEffect("request-recovery");

        assertThat(first.providerCallAuthorized()).isTrue();
        assertThat(first.delivery().effectState()).isEqualTo("EFFECT_STARTED");
        assertThat(redelivery.providerCallAuthorized()).isFalse();
        assertThat(redelivery.alreadyStarted()).isTrue();

        recorder.resultObserved(
                "request-recovery", "TERMINATION_DISPATCHED");
        var responseLossRedelivery = transaction(() ->
                recorder.authorizeEffect("request-recovery"));
        assertThat(responseLossRedelivery.providerCallAuthorized()).isFalse();
        assertThat(responseLossRedelivery.resultObserved()).isTrue();
        assertThat(outbox.findById(first.delivery().effectId()).orElseThrow()
                .getEffectState()).isEqualTo("RESULT_OBSERVED");
    }

    @Test
    void authorizationBindingClaimPersistsAndRejectsSameRequestDrift() {
        RuntimeTerminationIntentPort.RuntimeTerminationDelivery prepared =
                transaction(() -> recorder.recordIntent(
                        intent("request-claim", AUTHORIZATION_CLAIM_A)));
        RuntimeTerminationIntentPort.RuntimeTerminationDelivery replay =
                transaction(() -> recorder.recordIntent(
                        intent("request-claim", AUTHORIZATION_CLAIM_A)));

        assertThat(prepared.authorizationBindingClaim())
                .isEqualTo(AUTHORIZATION_CLAIM_A);
        assertThat(replay.effectId()).isEqualTo(prepared.effectId());
        assertThat(outbox.findById(prepared.effectId()).orElseThrow()
                .getEffectClaim()).isEqualTo(AUTHORIZATION_CLAIM_A);

        assertThatThrownBy(() -> transaction(() -> recorder.recordIntent(
                intent("request-claim", AUTHORIZATION_CLAIM_B))))
                .hasMessage("TERMINATION_DELIVERY_BINDING_MISMATCH");
        var unchanged = outbox.findById(prepared.effectId()).orElseThrow();
        assertThat(unchanged.getEffectState()).isEqualTo("PREPARED");
        assertThat(unchanged.getEffectClaim())
                .isEqualTo(AUTHORIZATION_CLAIM_A);
    }

    @Test
    void invalidAuthorizationBindingClaimRollsBackWithoutOutbox() {
        assertThatThrownBy(() -> transaction(() -> recorder.recordIntent(
                intent("request-invalid-claim", "NOT_A_CLAIM"))))
                .hasMessage(
                        "TERMINATION_AUTHORIZATION_BINDING_CLAIM_INVALID");

        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void principalDriftRejectsBeforeEffectAuthorizationStateChange() {
        transaction(() -> recorder.recordIntent(intent("request-principal-drift")));
        var canonical = canonicalTasks.findByTaskId("task-delivery")
                .orElseThrow();
        canonical.setUserId("owner-other");
        canonicalTasks.saveAndFlush(canonical);

        assertThatThrownBy(() -> transaction(() ->
                recorder.authorizeEffect("request-principal-drift")))
                .hasMessage("TERMINATION_EFFECT_BINDING_MISMATCH");

        assertThat(outbox.findByIdempotencyKey(
                "termination-intent:request-principal-drift").orElseThrow()
                .getEffectState()).isEqualTo("PREPARED");
    }

    @Test
    void invalidExactBindingRollsBackWithoutDurableAcceptanceDelivery() {
        assertThatThrownBy(() ->
                new TransactionTemplate(transactionManager).execute(status ->
                        recorder.recordIntent(new RuntimeTerminationIntentPort
                                .RuntimeTerminationIntent(
                                "request-invalid",
                                "task-delivery",
                                "session-delivery",
                                null,
                                "worker-delivery",
                                "provider-task-delivery",
                                "operation-delivery",
                                "binding-delivery"))))
                .isInstanceOf(RuntimeException.class);
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void acceptanceTransactionBindsExactOperationAndWorkerDigestToOwnerSnapshot() {
        transaction(() -> recorder.recordIntent(intent("request-bound")));

        assertThat(snapshots.findById("task-delivery").orElseThrow()
                .getOperationId()).isEqualTo("dispatch-delivery");
        assertThat(outbox.findByIdempotencyKey(
                "termination-intent:request-bound").orElseThrow()
                .getBindingDigest()).isEqualTo("binding-delivery");
    }

    @Test
    void exactWorkerCommandUsesSameProofAndHasItsOwnOnceAuthorization() {
        transaction(() -> recorder.recordIntent(intent("request-command")));
        assertThat(transaction(() ->
                recorder.authorizeEffect("request-command"))
                .providerCallAuthorized()).isTrue();

        var prepared = commandAuthorization.prepare(
                new WorkerLifecycleCommandAuthorizationPort
                        .WorkerLifecycleCommand(
                        "task-delivery", "codex-biz-worker",
                        "worker-delivery", "provider-task-delivery",
                        "termination-dispatch", "operation-delivery",
                        "exact-node-binding"));
        var authorized = commandAuthorization.authorize(
                prepared.effectId());
        var redelivery = commandAuthorization.authorize(
                prepared.effectId());

        assertThat(prepared.effectState()).isEqualTo("PREPARED");
        assertThat(authorized.providerCallAuthorized()).isTrue();
        assertThat(authorized.command().effectState())
                .isEqualTo("EFFECT_STARTED");
        assertThat(redelivery.providerCallAuthorized()).isFalse();
        assertThat(redelivery.alreadyStarted()).isTrue();
        var command = outbox.findById(prepared.effectId()).orElseThrow();
        var parent = outbox.findByIdempotencyKey(
                "termination-intent:request-command").orElseThrow();
        assertThat(command.getProofId()).isEqualTo(parent.getProofId());
        assertThat(command.getAggregateReferenceId())
                .isEqualTo(parent.getAggregateReferenceId());
        assertThat(command.getBindingDigest())
                .isEqualTo("exact-node-binding");
        assertThat(command.getDispatchId())
                .isEqualTo("termination-dispatch");
        assertThat(parent.getEffectClaim()).isEqualTo(
                RuntimeTerminationIntentPort.RuntimeTerminationIntent
                        .LEGACY_AUTHORIZATION_BINDING_CLAIM);
        assertThat(command.getEffectClaim())
                .isEqualTo("CODEX_WORKER_TERMINATION_CALL");
    }

    @Test
    void terminationProofFencingUsesAuthorityClockAcrossReceiptAndWorkerCommand() {
        var proof = proofs.findById("proof-delivery").orElseThrow();
        proof.setAcquiredAt(AUTHORITY_NOW.minusMinutes(1));
        proof.setLastVerifiedAt(AUTHORITY_NOW);
        proof.setExpiresAt(AUTHORITY_NOW.plusMinutes(5));
        proofs.saveAndFlush(proof);
        assertThat(LocalDateTime.now()).isAfter(proof.getExpiresAt());

        transaction(() -> recorder.recordIntent(intent("request-authority-clock")));
        assertThat(transaction(() -> recorder.authorizeEffect(
                "request-authority-clock")).providerCallAuthorized()).isTrue();

        var prepared = commandAuthorization.prepare(
                new WorkerLifecycleCommandAuthorizationPort
                        .WorkerLifecycleCommand(
                        "task-delivery", "codex-biz-worker",
                        "worker-delivery", "provider-task-delivery",
                        "termination-dispatch", "operation-delivery",
                        "authority-clock-binding"));
        assertThat(commandAuthorization.authorize(prepared.effectId())
                .providerCallAuthorized()).isTrue();
    }

    @Test
    void receiptAdmissionRejectsEveryOwnerFenceAndExactProofReferenceDrift() {
        var task = snapshots.findById("task-delivery").orElseThrow();
        task.setOwnershipMode("SHADOW");
        snapshots.saveAndFlush(task);
        assertAdmissionRejected("request-task-mode");
        task = snapshots.findById("task-delivery").orElseThrow();
        task.setOwnershipMode("ENFORCED");
        snapshots.saveAndFlush(task);

        task = snapshots.findById("task-delivery").orElseThrow();
        task.setStateGeneration("stale-generation");
        snapshots.saveAndFlush(task);
        assertAdmissionRejected("request-task-generation");
        task = snapshots.findById("task-delivery").orElseThrow();
        task.setStateGeneration("generation-delivery");
        snapshots.saveAndFlush(task);

        task = snapshots.findById("task-delivery").orElseThrow();
        task.setInstanceEpoch("stale-epoch");
        snapshots.saveAndFlush(task);
        assertAdmissionRejected("request-stale-epoch");
        task = snapshots.findById("task-delivery").orElseThrow();
        task.setInstanceEpoch("epoch-delivery");
        snapshots.saveAndFlush(task);

        task = snapshots.findById("task-delivery").orElseThrow();
        task.setAvailability(LifecycleAvailability.OFFLINE_FROZEN.name());
        snapshots.saveAndFlush(task);
        assertAdmissionRejected("request-task-not-ready");
        task = snapshots.findById("task-delivery").orElseThrow();
        task.setAvailability(LifecycleAvailability.READY.name());
        snapshots.saveAndFlush(task);

        task = snapshots.findById("task-delivery").orElseThrow();
        task.setConflictState(
                LifecycleConflictState.EVIDENCE_CONFLICT.name());
        snapshots.saveAndFlush(task);
        assertAdmissionRejected("request-task-conflict");
        task = snapshots.findById("task-delivery").orElseThrow();
        task.setConflictState(LifecycleConflictState.NONE.name());
        snapshots.saveAndFlush(task);

        task = snapshots.findById("task-delivery").orElseThrow();
        task.setProviderTaskId("different-provider-task");
        snapshots.saveAndFlush(task);
        assertAdmissionRejected("request-task-provider-binding");
        task = snapshots.findById("task-delivery").orElseThrow();
        task.setProviderTaskId("provider-task-delivery");
        snapshots.saveAndFlush(task);

        var worker = workers.findById("worker-delivery").orElseThrow();
        worker.setOwnershipMode("SHADOW");
        workers.saveAndFlush(worker);
        assertAdmissionRejected("request-worker-mode");
        worker = workers.findById("worker-delivery").orElseThrow();
        worker.setOwnershipMode("ENFORCED");
        workers.saveAndFlush(worker);

        worker = workers.findById("worker-delivery").orElseThrow();
        worker.setStateGeneration("stale-generation");
        workers.saveAndFlush(worker);
        assertAdmissionRejected("request-stale-generation");
        worker = workers.findById("worker-delivery").orElseThrow();
        worker.setStateGeneration("generation-delivery");
        workers.saveAndFlush(worker);

        worker = workers.findById("worker-delivery").orElseThrow();
        worker.setInstanceEpoch("stale-epoch");
        workers.saveAndFlush(worker);
        assertAdmissionRejected("request-worker-epoch");
        worker = workers.findById("worker-delivery").orElseThrow();
        worker.setInstanceEpoch("epoch-delivery");
        workers.saveAndFlush(worker);

        worker = workers.findById("worker-delivery").orElseThrow();
        worker.setAvailability(LifecycleAvailability.OFFLINE_FROZEN.name());
        workers.saveAndFlush(worker);
        assertAdmissionRejected("request-worker-not-ready");
        worker = workers.findById("worker-delivery").orElseThrow();
        worker.setAvailability(LifecycleAvailability.READY.name());
        workers.saveAndFlush(worker);

        worker = workers.findById("worker-delivery").orElseThrow();
        worker.setConflictState(
                LifecycleConflictState.EVIDENCE_CONFLICT.name());
        workers.saveAndFlush(worker);
        assertAdmissionRejected("request-worker-conflict");
        worker = workers.findById("worker-delivery").orElseThrow();
        worker.setConflictState(LifecycleConflictState.NONE.name());
        workers.saveAndFlush(worker);

        var session = sessions.findById("session-delivery").orElseThrow();
        session.setOwnershipMode("SHADOW");
        sessions.saveAndFlush(session);
        assertAdmissionRejected("request-session-mode");
        session = sessions.findById("session-delivery").orElseThrow();
        session.setOwnershipMode("ENFORCED");
        sessions.saveAndFlush(session);

        session = sessions.findById("session-delivery").orElseThrow();
        session.setAvailability(
                LifecycleAvailability.OFFLINE_FROZEN.name());
        sessions.saveAndFlush(session);
        assertAdmissionRejected("request-session-not-ready");
        session = sessions.findById("session-delivery").orElseThrow();
        session.setAvailability(LifecycleAvailability.READY.name());
        sessions.saveAndFlush(session);

        session = sessions.findById("session-delivery").orElseThrow();
        session.setConflictState(
                LifecycleConflictState.EVIDENCE_CONFLICT.name());
        sessions.saveAndFlush(session);
        assertAdmissionRejected("request-session-conflict");
        session = sessions.findById("session-delivery").orElseThrow();
        session.setConflictState(LifecycleConflictState.NONE.name());
        sessions.saveAndFlush(session);

        session = sessions.findById("session-delivery").orElseThrow();
        session.setPhysicalWorkerId("different-worker");
        sessions.saveAndFlush(session);
        assertAdmissionRejected("request-session-binding");
        session = sessions.findById("session-delivery").orElseThrow();
        session.setPhysicalWorkerId("worker-delivery");
        sessions.saveAndFlush(session);

        var canonical = canonicalTasks.findByTaskId("task-delivery")
                .orElseThrow();
        canonical.setProviderType("different-provider");
        canonicalTasks.saveAndFlush(canonical);
        assertAdmissionRejected("request-canonical-provider");
        canonical = canonicalTasks.findByTaskId("task-delivery")
                .orElseThrow();
        canonical.setProviderType("codex-biz-worker");
        canonicalTasks.saveAndFlush(canonical);
        canonical = canonicalTasks.findByTaskId("task-delivery")
                .orElseThrow();
        canonical.setProviderTaskId("different-provider-task");
        canonicalTasks.saveAndFlush(canonical);
        assertAdmissionRejected("request-canonical-provider-task");
        canonical = canonicalTasks.findByTaskId("task-delivery")
                .orElseThrow();
        canonical.setProviderTaskId("provider-task-delivery");
        canonicalTasks.saveAndFlush(canonical);
        canonical = canonicalTasks.findByTaskId("task-delivery")
                .orElseThrow();
        canonical.setWorkerId("different-worker");
        canonicalTasks.saveAndFlush(canonical);
        assertAdmissionRejected("request-canonical-worker");
        canonical = canonicalTasks.findByTaskId("task-delivery")
                .orElseThrow();
        canonical.setWorkerId("worker-delivery");
        canonicalTasks.saveAndFlush(canonical);
        canonical = canonicalTasks.findByTaskId("task-delivery")
                .orElseThrow();
        canonical.setSessionId("different-session");
        canonicalTasks.saveAndFlush(canonical);
        assertAdmissionRejected("request-canonical-session");
        canonical = canonicalTasks.findByTaskId("task-delivery")
                .orElseThrow();
        canonical.setSessionId("session-delivery");
        canonicalTasks.saveAndFlush(canonical);

        var reference = references.findById(
                "proof-delivery:SESSION:session-delivery").orElseThrow();
        reference.setAggregateId("different-session");
        references.saveAndFlush(reference);
        assertAdmissionRejected("request-reference-drift");

        assertThat(outbox.findAll()).isEmpty();
    }

    private void assertAdmissionRejected(String requestId) {
        assertThatThrownBy(() -> transaction(() ->
                recorder.recordIntent(intent(requestId))))
                .isInstanceOf(RuntimeException.class);
    }

    private void enrollFixture() {
        var identity = new com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity(
                "worker-delivery", "generation-delivery", "epoch-delivery");
        reconciliation.commit(
                new com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot(
                        identity, 1, 1, true,
                        java.util.List.of(), java.util.List.of()), null);
        var canonical = new com.foggy.navigator.common.entity.SessionTaskEntity();
        canonical.setTaskId("task-delivery");
        canonical.setSessionId("session-delivery");
        canonical.setProviderType("codex-biz-worker");
        canonical.setProviderTaskId("provider-task-delivery");
        canonical.setWorkerId("worker-delivery");
        canonical.setUserId("owner-delivery");
        canonical.setTenantId("tenant-delivery");
        canonical.setStatus("RUNNING");
        canonicalTasks.saveAndFlush(canonical);
        LocalDateTime now = LocalDateTime.now();
        var proof = new com.foggy.navigator.session.lifecycle.persistence
                .LifecycleWriterProofEntity();
        proof.setProofId("proof-delivery");
        proof.setGenerationId("writer-generation");
        proof.setControllerInventoryDigest("inventory-delivery");
        proof.setHolderInstanceId("fixture-instance");
        proof.setProofVersion(1);
        proof.setStatus("ACTIVE");
        proof.setAcquiredAt(now);
        proof.setLastVerifiedAt(now);
        proof.setExpiresAt(now.plusMinutes(5));
        proofs.saveAndFlush(proof);
        var request = new LifecycleEnrollmentGate.EnrollmentRequest(
                "codex-biz-worker", true, true, false,
                true, true, true, true, true, true,
                Set.of(
                        "AUTHENTICATED_LIFECYCLE_V1",
                        "FENCED_INVENTORY_V1",
                        "DURABLE_LIFECYCLE_FACTS_V1",
                        "MONOTONIC_ACK_V1",
                        "EXACT_DISPATCH_DEDUPE_V1",
                        "DURABLE_PROVIDER_TASK_ID_V1",
                        "TERMINATION_ATOMIC_CAPABILITY_V1"),
                true, now.plusMinutes(5), now);
        enrollment.enroll(new LifecycleEnrollmentService.EnrollmentCommand(
                request, identity, "session-delivery",
                new com.foggy.navigator.spi.lifecycle.WorkerLifecycleTask(
                        "task-delivery", "provider-task-delivery",
                        com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode.SHADOW,
                        "dispatch-delivery", "JCS_SHA256_V1",
                        "worker-binding-digest", "RUNNING", 1),
                "proof-delivery", "writer-generation"));
    }

    private RuntimeTerminationIntentPort.RuntimeTerminationIntent intent(
            String requestId) {
        return new RuntimeTerminationIntentPort.RuntimeTerminationIntent(
                requestId,
                "task-delivery",
                "session-delivery",
                "codex-biz-worker",
                "worker-delivery",
                "provider-task-delivery",
                "operation-delivery",
                "operation-delivery",
                "ENFORCED",
                "generation-delivery",
                "epoch-delivery",
                "JCS_SHA256_V1",
                "binding-delivery",
                "owner-delivery",
                "tenant-delivery");
    }

    private RuntimeTerminationIntentPort.RuntimeTerminationIntent intent(
            String requestId,
            String authorizationBindingClaim) {
        return new RuntimeTerminationIntentPort.RuntimeTerminationIntent(
                requestId,
                "task-delivery",
                "session-delivery",
                "codex-biz-worker",
                "worker-delivery",
                "provider-task-delivery",
                "operation-delivery",
                "operation-delivery",
                "ENFORCED",
                "generation-delivery",
                "epoch-delivery",
                "JCS_SHA256_V1",
                "binding-delivery",
                "owner-delivery",
                "tenant-delivery",
                authorizationBindingClaim);
    }

    private <T> T transaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> work.get());
    }
}
