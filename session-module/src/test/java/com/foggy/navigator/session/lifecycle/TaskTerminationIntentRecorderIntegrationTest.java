package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleCommandAuthorizationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        WorkerLifecycleReconciliationCommitService.class
})
class TaskTerminationIntentRecorderIntegrationTest {
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
                "binding-delivery");
    }

    private <T> T transaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> work.get());
    }
}
