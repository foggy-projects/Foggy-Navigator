package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofReferenceEntity;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofReferenceRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleWriterProofRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(classes = {
        TaskLifecycleOwnerVerticalIntegrationTest.Config.class,
        WriterExclusivityProofService.class
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
    PlatformTransactionManager transactionManager;

    private final AtomicInteger providerCalls = new AtomicInteger();

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
    void lossFirstCommitsBeforeAuthorizationAndProviderCountRemainsZero()
            throws Exception {
        CountDownLatch proofLocked = new CountDownLatch(1);
        CountDownLatch authorizationStarted = new CountDownLatch(1);
        AtomicReference<Throwable> authorizationFailure = new AtomicReference<>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var loss = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        var proof = proofs.findForUpdate("proof-1").orElseThrow();
                        proof.setStatus("QUARANTINED");
                        proofs.save(proof);
                        proofLocked.countDown();
                        await(authorizationStarted);
                    }));
            var authorization = executor.submit(() -> {
                await(proofLocked);
                authorizationStarted.countDown();
                try {
                    authorizeProviderOnce();
                } catch (Throwable failure) {
                    authorizationFailure.set(failure);
                }
            });
            loss.get(10, TimeUnit.SECONDS);
            authorization.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(authorizationFailure.get())
                .hasMessage("LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        assertThat(providerCalls).hasValue(0);
        assertThat(outbox.findById("effect-1").orElseThrow().getEffectState())
                .isEqualTo("CLAIMED");
    }

    @Test
    void authorizationFirstIsAtMostOnceAndQuarantineCannotAuthorizeRedelivery()
            throws Exception {
        CountDownLatch effectStarted = new CountDownLatch(1);
        CountDownLatch quarantineStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var authorization = executor.submit(() ->
                    new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> {
                                var decision = service.authorizeEffect(
                                        command(), NOW);
                                assertThat(decision.providerCallAuthorized()).isTrue();
                                effectStarted.countDown();
                                await(quarantineStarted);
                            }));
            var loss = executor.submit(() -> {
                await(effectStarted);
                quarantineStarted.countDown();
                service.quarantine("proof-1");
            });
            authorization.get(10, TimeUnit.SECONDS);
            providerCalls.incrementAndGet();
            loss.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        var redelivery = service.authorizeEffect(command(), NOW.plusSeconds(1));
        if (redelivery.providerCallAuthorized()) providerCalls.incrementAndGet();
        assertThat(redelivery.alreadyStarted()).isTrue();
        assertThat(providerCalls).hasValue(1);
    }

    private void authorizeProviderOnce() {
        var decision = service.authorizeEffect(command(), NOW);
        if (decision.providerCallAuthorized()) providerCalls.incrementAndGet();
    }

    private WriterExclusivityProofService.EffectAuthorizationCommand command() {
        return new WriterExclusivityProofService.EffectAuthorizationCommand(
                "effect-1",
                "proof-1",
                "reference-1",
                ProofAggregateType.TASK,
                "task-proof-1",
                "generation-1",
                "inventory-1",
                "TASK_CREATE_PROVIDER_CALL");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("fixture latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
