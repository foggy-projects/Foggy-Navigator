package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(classes = {
        TaskLifecycleOwnerVerticalIntegrationTest.Config.class,
        TaskTerminationIntentRecorder.class
})
class TaskTerminationIntentRecorderIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired
    RuntimeTerminationIntentPort recorder;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleEffectOutboxRepository outbox;
    @org.springframework.beans.factory.annotation.Autowired
    com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository
            snapshots;
    @org.springframework.beans.factory.annotation.Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        outbox.deleteAll();
        snapshots.deleteAll();
    }

    @Test
    void committedPreparedDeliveryCanContinueAfterRestartAndNeverAuthorizeTwice() {
        RuntimeTerminationIntentPort.RuntimeTerminationDelivery prepared =
                new TransactionTemplate(transactionManager).execute(status ->
                        recorder.recordIntent(intent("request-recovery")));
        assertThat(prepared.effectState()).isEqualTo("PREPARED");

        // A new service instance models a Java restart after the acceptance
        // transaction committed but before provider dispatch.
        TaskTerminationIntentRecorder restarted =
                new TaskTerminationIntentRecorder(outbox, snapshots);
        var first = transaction(() ->
                restarted.authorizeEffect("request-recovery"));
        var redelivery = recorder.authorizeEffect("request-recovery");

        assertThat(first.providerCallAuthorized()).isTrue();
        assertThat(first.delivery().effectState()).isEqualTo("EFFECT_STARTED");
        assertThat(redelivery.providerCallAuthorized()).isFalse();
        assertThat(redelivery.alreadyStarted()).isTrue();

        recorder.resultObserved(
                "request-recovery", "TERMINATION_DISPATCHED");
        var responseLossRedelivery = transaction(() ->
                restarted.authorizeEffect("request-recovery"));
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
        TaskLifecycleSnapshotEntity snapshot =
                new TaskLifecycleSnapshotEntity();
        snapshot.setTaskId("task-delivery");
        snapshot.setSessionId("session-delivery");
        snapshot.setPhysicalWorkerId("worker-delivery");
        snapshot.setProviderTaskId("provider-task-delivery");
        snapshot.setStateGeneration("generation-delivery");
        snapshot.setInstanceEpoch("epoch-delivery");
        snapshot.setOwnershipMode("ENFORCED");
        snapshot.setDispatchId("dispatch-delivery");
        snapshot.setOperationId("dispatch-delivery");
        snapshot.setSafeBindingDigestVersion("JCS_SHA256_V1");
        snapshot.setSafeBindingDigest("worker-binding-digest");
        snapshot.setCanonicalPhase("OPEN");
        snapshot.setAvailability("READY");
        snapshot.setConflictState("NONE");
        snapshot.setCleanupState("NOT_REQUIRED");
        snapshot.setFactCursor(0L);
        snapshot.setPolicyVersion("ARCH-001-MVP-A");
        snapshot.setWriterGenerationId("writer-generation");
        snapshot.setSnapshotJson("{}");
        snapshots.saveAndFlush(snapshot);

        transaction(() -> recorder.recordIntent(intent("request-bound")));

        assertThat(snapshots.findById("task-delivery").orElseThrow()
                .getOperationId()).isEqualTo("operation-delivery");
        assertThat(outbox.findByIdempotencyKey(
                "termination-intent:request-bound").orElseThrow()
                .getBindingDigest()).isEqualTo("worker-binding-digest");
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
