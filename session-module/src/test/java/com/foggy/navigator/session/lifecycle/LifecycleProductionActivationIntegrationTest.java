package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.*;
import com.foggy.navigator.spi.lifecycle.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(LifecycleProductionActivationIntegrationTest.Config.class)
@ActiveProfiles("arch001-activation-test")
@TestPropertySource(properties =
        "navigator.runtime-audit.termination-receipt-enabled=true")
class LifecycleProductionActivationIntegrationTest {
    private static final String TARGET_ID = "arch001-act-target-fixture";
    private static final String RUN_ID = "arch001-act-run-fixture";
    private static final String WORKER_ID = "synthetic-arch001-worker";
    private static final String SESSION_ID = "synthetic-session-1";
    private static final String TASK_ID = "synthetic-task-1";
    private static final String STATE_GENERATION = "worker-generation-1";
    private static final String INSTANCE_EPOCH = "worker-epoch-1";
    private static final String MODEL_CONFIG = "synthetic-model-config";
    private static final String MODEL = "gpt-5.6-sol";
    private static final String PROMPT_DIGEST = "b".repeat(64);
    private static final String BINDING_DIGEST = "binding-digest-1";
    private static final String DISPATCH_ID = "dispatch-1";

    @TestConfiguration
    @Profile("arch001-activation-test")
    @Import({
            TaskLifecycleOwnerVerticalIntegrationTest.Config.class,
            LifecycleActivationAuthorityService.class,
            LifecycleProductionAdmissionService.class
    })
    static class Config {
        @Bean
        LifecycleActivationProperties lifecycleActivationProperties() {
            LifecycleActivationProperties value =
                    new LifecycleActivationProperties();
            value.setControlEnabled(true);
            value.setAdmissionEnabled(true);
            value.setExactTargetId(TARGET_ID);
            value.setInstanceId("activation-instance-1");
            value.setCandidateHead(
                    "fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00");
            value.setCandidatePatchSha256("a".repeat(64));
            value.setOwnerProtocol(1);
            value.setProofLease(Duration.ofSeconds(30));
            value.setInstanceTtl(Duration.ofSeconds(60));
            return value;
        }

        @Bean
        @Primary
        MutableAuthorityFixture mutableAuthorityFixture(
                LifecycleActivationProperties properties,
                ObjectMapper objectMapper) {
            return new MutableAuthorityFixture(properties, objectMapper);
        }

    }

    @Autowired LifecycleActivationAuthorityService authority;
    @Autowired LifecycleProductionAdmissionService admission;
    @Autowired LifecycleActivationProperties properties;
    @Autowired MutableAuthorityFixture fixture;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired LifecycleActivationTargetRepository targets;
    @Autowired LifecycleWriterGenerationRepository generations;
    @Autowired LifecycleWriterInstanceRegistrationRepository instances;
    @Autowired LifecycleWriterProofRepository proofs;
    @Autowired LifecycleWriterProofReferenceRepository references;
    @Autowired WorkerLifecycleSnapshotRepository workers;
    @Autowired SessionLifecycleSnapshotRepository sessions;
    @Autowired TaskLifecycleSnapshotRepository tasks;
    @Autowired LifecycleFactRepository facts;
    @Autowired LifecycleEffectOutboxRepository outbox;
    @Autowired SessionTaskRepository canonicalTasks;

    @BeforeEach
    void reset() {
        outbox.deleteAll();
        references.deleteAll();
        facts.deleteAll();
        tasks.deleteAll();
        sessions.deleteAll();
        workers.deleteAll();
        proofs.deleteAll();
        instances.deleteAll();
        generations.deleteAll();
        targets.deleteAll();
        canonicalTasks.deleteAll();
        properties.setControlEnabled(true);
        properties.setAdmissionEnabled(true);
        properties.setLocalDevelopmentTargetEnabled(false);
        properties.setExactTargetId(TARGET_ID);
        properties.setInstanceId("activation-instance-1");
        ReflectionTestUtils.setField(
                authority, "terminationReceiptEnabled", true);
        fixture.reset();
    }

    @Test
    void boundedLocalDevelopmentTargetRequiresSeparateOptInAndUsesExactProvider() {
        fixture.localDevelopmentTarget = true;

        assertThatThrownBy(() -> authority.registerConfiguredTarget(TARGET_ID))
                .hasMessage(LifecycleActivationReason
                        .LOCAL_DEVELOPMENT_TARGET_DISABLED);
        assertThat(targets.count()).isZero();

        properties.setLocalDevelopmentTargetEnabled(true);
        prepareAuthorityAndReservation(
                TASK_ID, SESSION_ID, true);
        var authorization = admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID));

        assertThat(authorization.providerCallAuthorized()).isTrue();
        assertThat(workers.findById(WORKER_ID).orElseThrow()
                .getOwnershipMode()).isEqualTo("ENFORCED");
        assertThat(sessions.findById(SESSION_ID).orElseThrow()
                .getOwnershipMode()).isEqualTo("ENFORCED");
        assertThat(tasks.findById(TASK_ID).orElseThrow()
                .getOwnershipMode()).isEqualTo("ENFORCED");
        assertThat(outbox.findAll()).singleElement()
                .extracting(LifecycleEffectOutboxEntity::getProviderType)
                .isEqualTo("codex-worker");
    }

    @Test
    void productionAdmissionIsAtomicBeforeProviderEffect() {
        prepareAuthorityAndReservation(TASK_ID, SESSION_ID);
        AtomicInteger providerEffects = new AtomicInteger();

        var authorization = admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID));
        if (authorization.providerCallAuthorized()) {
            providerEffects.incrementAndGet();
        }

        assertThat(providerEffects).hasValue(1);
        assertThat(references.countByProofIdAndReleasedAtIsNull(
                target().getProofId())).isEqualTo(3);
        assertThat(workers.findById(WORKER_ID).orElseThrow()
                .getOwnershipMode()).isEqualTo("ENFORCED");
        assertThat(sessions.findById(SESSION_ID).orElseThrow()
                .getForegroundTaskId()).isEqualTo(TASK_ID);
        assertThat(tasks.findById(TASK_ID).orElseThrow()
                .getSafeBindingDigest()).isEqualTo(BINDING_DIGEST);
        assertThat(facts.findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc(
                "TASK", TASK_ID))
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.getFactType())
                            .isEqualTo("TASK_DISPATCH_RESERVED");
                    assertThat(value.getContentFreePayloadJson())
                            .isNotEqualTo("{}");
                });
        assertThat(outbox.findAll()).singleElement()
                .extracting(LifecycleEffectOutboxEntity::getEffectState)
                .isEqualTo("EFFECT_STARTED");

        admission.observeAcceptedDisposition(
                new LifecycleProductionAdmissionService.AcceptedDisposition(
                        TASK_ID, SESSION_ID, identity(), "provider-task-1",
                        DISPATCH_ID, "JCS_SHA256_V1", BINDING_DIGEST, 1));
        assertThat(tasks.findById(TASK_ID).orElseThrow()
                .getProviderTaskId()).isEqualTo("provider-task-1");
        assertThat(outbox.findAll()).singleElement()
                .extracting(LifecycleEffectOutboxEntity::getEffectState)
                .isEqualTo("COMPLETED");
        assertThat(target().getStatus()).isEqualTo("CONSUMED");
        assertThat(facts.findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc(
                "TASK", TASK_ID))
                .extracting(value -> value.getContentFreePayloadJson())
                .allSatisfy(payload -> assertThat(payload).isNotEqualTo("{}"));
    }

    @Test
    void cleanFrozenShadowWorkerCanBindItsRestartedInstanceEpoch() {
        WorkerLifecycleSnapshotEntity frozen = new WorkerLifecycleSnapshotEntity();
        frozen.setPhysicalWorkerId(WORKER_ID);
        frozen.setOwnershipMode("SHADOW");
        frozen.setStateGeneration(STATE_GENERATION);
        frozen.setInstanceEpoch("provisioning-instance-epoch");
        frozen.setAvailability(LifecycleAvailability.OFFLINE_FROZEN.name());
        frozen.setConflictState(LifecycleConflictState.NONE.name());
        frozen.setFactCursor(0);
        frozen.setPolicyVersion("ARCH-001-ACT-001");
        frozen.setWriterGenerationId(null);
        frozen.setSnapshotJson("{}");
        workers.saveAndFlush(frozen);

        prepareAuthority();

        WorkerLifecycleSnapshotEntity rebound = workers.findById(WORKER_ID)
                .orElseThrow();
        assertThat(rebound.getStateGeneration()).isEqualTo(STATE_GENERATION);
        assertThat(rebound.getInstanceEpoch()).isEqualTo(INSTANCE_EPOCH);
        assertThat(rebound.getAvailability())
                .isEqualTo(LifecycleAvailability.READY.name());
        assertThat(target().getStatus()).isEqualTo("READY");
    }

    @Test
    void nonFrozenShadowWorkerCannotRebindAChangedInstanceEpoch() {
        WorkerLifecycleSnapshotEntity active = new WorkerLifecycleSnapshotEntity();
        active.setPhysicalWorkerId(WORKER_ID);
        active.setOwnershipMode("SHADOW");
        active.setStateGeneration(STATE_GENERATION);
        active.setInstanceEpoch("another-live-instance-epoch");
        active.setAvailability(LifecycleAvailability.READY.name());
        active.setConflictState(LifecycleConflictState.NONE.name());
        active.setFactCursor(0);
        active.setPolicyVersion("ARCH-001-ACT-001");
        active.setWriterGenerationId(null);
        active.setSnapshotJson("{}");
        workers.saveAndFlush(active);
        authority.registerConfiguredTarget(TARGET_ID);

        assertThatThrownBy(() -> authority.acquireConfiguredProof(TARGET_ID))
                .hasMessage(LifecycleActivationReason.WORKER_IDENTITY_MISMATCH);
        assertThat(workers.findById(WORKER_ID).orElseThrow()
                .getInstanceEpoch()).isEqualTo("another-live-instance-epoch");
    }

    @Test
    void durableCommandConflictRollsBackEveryEnrollmentWriteAndEffectStaysZero() {
        prepareAuthorityAndReservation(TASK_ID, SESSION_ID);
        LifecycleEffectOutboxEntity conflict = new LifecycleEffectOutboxEntity();
        conflict.setEffectId("preexisting-conflict");
        conflict.setAggregateType("TASK");
        conflict.setAggregateId("other-task");
        conflict.setEffectType("TASK_CREATE_DISPATCH");
        conflict.setEffectClass("EXTERNAL_PROVIDER_ONCE");
        conflict.setEffectState("COMPLETED");
        conflict.setIdempotencyKey(stableId(
                "activation-task-create", TARGET_ID + ":" + TASK_ID));
        conflict.setContentFreePayloadJson("{}");
        outbox.saveAndFlush(conflict);
        AtomicInteger providerEffects = new AtomicInteger();

        assertThatThrownBy(() -> {
            var authorization = admission.admitAndAuthorizeProviderEffect(
                    providerEffect(TASK_ID, SESSION_ID));
            if (authorization.providerCallAuthorized()) {
                providerEffects.incrementAndGet();
            }
        }).isInstanceOf(RuntimeException.class);

        assertThat(providerEffects).hasValue(0);
        assertThat(tasks.findById(TASK_ID)).isEmpty();
        assertThat(sessions.findById(SESSION_ID)).isEmpty();
        assertThat(workers.findById(WORKER_ID).orElseThrow()
                .getOwnershipMode()).isEqualTo("SHADOW");
        assertThat(references.countByProofIdAndReleasedAtIsNull(
                target().getProofId())).isZero();
        assertThat(facts.findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc(
                "TASK", TASK_ID)).isEmpty();
        assertThat(target().getStatus()).isEqualTo("QUARANTINED");
        assertThat(proofs.findById(target().getProofId()).orElseThrow()
                .getStatus()).isEqualTo("QUARANTINED");
    }

    @Test
    void crossTupleAndExistingSessionSpoofsAreDeniedBeforeReservation() {
        prepareAuthority();
        var wrongTenant = request(TASK_ID, SESSION_ID, null, "wrong-tenant");
        assertThatThrownBy(() -> admission.reserveProductionAdmission(wrongTenant))
                .hasMessage(LifecycleActivationReason.EXACT_TUPLE_MISMATCH);
        var existingSession = request(
                TASK_ID, SESSION_ID, "caller-existing-session",
                "synthetic-arch001-tenant");
        assertThatThrownBy(() ->
                admission.reserveProductionAdmission(existingSession))
                .hasMessage(LifecycleActivationReason.NEW_SESSION_REQUIRED);
        assertThat(target().getStatus()).isEqualTo("READY");
        assertThat(tasks.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void localDevelopmentAdmissionCanonicalizesLogicalCodexModelToPhysicalTuple() {
        fixture.localDevelopmentTarget = true;
        fixture.manifestModel = "gpt-5.6-luna";
        properties.setLocalDevelopmentTargetEnabled(true);
        prepareAuthority();

        var reservation = admission.reserveProductionAdmission(request(
                TASK_ID, SESSION_ID, null,
                "synthetic-arch001-tenant", true,
                MODEL_CONFIG, "codex-luna:high"));

        assertThat(reservation.activationRequired()).isTrue();
        assertThat(target().getStatus()).isEqualTo("RESERVED");
        assertThat(target().getReservedTaskId()).isEqualTo(TASK_ID);
        assertThat(tasks.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void providerEffectAdmissionUsesTheSameCanonicalModelTupleAsReservation() {
        fixture.localDevelopmentTarget = true;
        fixture.manifestModel = "gpt-5.6-luna";
        properties.setLocalDevelopmentTargetEnabled(true);
        prepareAuthority();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            admission.reserveProductionAdmission(request(
                    TASK_ID, SESSION_ID, null,
                    "synthetic-arch001-tenant", true,
                    MODEL_CONFIG, "codex-luna:high"));
            SessionTaskEntity canonical = canonical(TASK_ID, SESSION_ID, true);
            canonical.setModel("codex-luna:high");
            canonicalTasks.save(canonical);
        });

        var authorization = admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID));

        assertThat(authorization.providerCallAuthorized()).isTrue();
        assertThat(target().getStatus()).isEqualTo("ADMITTED");
        assertThat(tasks.findById(TASK_ID).orElseThrow().getOwnershipMode())
                .isEqualTo("ENFORCED");
        assertThat(references.countByProofIdAndReleasedAtIsNull(
                target().getProofId())).isEqualTo(3);
        assertThat(facts.findByAggregateTypeAndAggregateIdOrderBySourceSequenceAsc(
                "TASK", TASK_ID)).hasSize(1);
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void providerEffectCanonicalModelMismatchQuarantinesOneShotReservation() {
        fixture.localDevelopmentTarget = true;
        fixture.manifestModel = "gpt-5.6-luna";
        properties.setLocalDevelopmentTargetEnabled(true);
        prepareAuthority();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            admission.reserveProductionAdmission(request(
                    TASK_ID, SESSION_ID, null,
                    "synthetic-arch001-tenant", true,
                    MODEL_CONFIG, "codex-luna:high"));
            SessionTaskEntity canonical = canonical(TASK_ID, SESSION_ID, true);
            canonical.setModel("codex-terra:high");
            canonicalTasks.save(canonical);
        });

        assertThatThrownBy(() -> admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID)))
                .hasMessage(LifecycleActivationReason.ADMISSION_BINDING_MISMATCH);

        assertThat(target().getStatus()).isEqualTo("QUARANTINED");
        assertThat(proofs.findById(target().getProofId()).orElseThrow()
                .getStatus()).isEqualTo("QUARANTINED");
        assertThat(workers.findById(WORKER_ID).orElseThrow()
                .getOwnershipMode()).isEqualTo("SHADOW");
        assertThat(tasks.count()).isZero();
        assertThat(sessions.count()).isZero();
        assertThat(references.count()).isZero();
        assertThat(facts.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void localDevelopmentManifestMustPinCanonicalPhysicalModelFamily() {
        fixture.localDevelopmentTarget = true;
        fixture.manifestModel = "codex-luna:high";
        properties.setLocalDevelopmentTargetEnabled(true);

        assertThatThrownBy(() -> authority.registerConfiguredTarget(TARGET_ID))
                .hasMessage(LifecycleActivationReason.MANIFEST_INVALID);
        assertThat(targets.count()).isZero();
    }

    @Test
    void localDevelopmentModelCanonicalizationStillFailsClosedForNonExactTuple() {
        fixture.localDevelopmentTarget = true;
        fixture.manifestModel = "gpt-5.6-luna";
        properties.setLocalDevelopmentTargetEnabled(true);
        prepareAuthority();

        assertThatThrownBy(() -> admission.reserveProductionAdmission(request(
                TASK_ID, SESSION_ID, null,
                "synthetic-arch001-tenant", true,
                MODEL_CONFIG, "codex-terra:high")))
                .hasMessage(LifecycleActivationReason.EXACT_TUPLE_MISMATCH);
        assertThatThrownBy(() -> admission.reserveProductionAdmission(request(
                TASK_ID, SESSION_ID, null,
                "synthetic-arch001-tenant", true,
                MODEL_CONFIG, "codex-luna:not-an-effort")))
                .hasMessage(LifecycleActivationReason.EXACT_TUPLE_MISMATCH);
        assertThatThrownBy(() -> admission.reserveProductionAdmission(request(
                TASK_ID, SESSION_ID, null,
                "synthetic-arch001-tenant", true,
                "different-model-config", "codex-luna:high")))
                .hasMessage(LifecycleActivationReason.EXACT_TUPLE_MISMATCH);

        assertThat(target().getStatus()).isEqualTo("READY");
        assertThat(target().getReservedTaskId()).isNull();
        assertThat(tasks.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void authorityAbsenceOrDisabledAdmissionNeverAuthorizesEffect() {
        properties.setAdmissionEnabled(false);
        assertThat(admission.ownershipModeForTask("any-task"))
                .isEqualTo(LifecycleOwnershipMode.SHADOW);
        assertThat(admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID)).activationRequired())
                .isFalse();

        properties.setAdmissionEnabled(true);
        assertThatThrownBy(() -> admission.ownershipModeForTask("any-task"))
                .hasMessage(LifecycleActivationReason.TARGET_NOT_REGISTERED);
    }

    @Test
    void identityDriftQuarantinesProofAndAllReferencedAggregates() {
        prepareAuthorityAndReservation(TASK_ID, SESSION_ID);
        admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID));
        fixture.readiness = fixture.readiness(
                new WorkerLifecycleIdentity(
                        WORKER_ID, "drift-generation", "drift-epoch"));

        assertThatThrownBy(authority::observeAndRenewConfiguredProof)
                .hasMessage(LifecycleActivationReason.WORKER_IDENTITY_MISMATCH);

        assertThat(target().getStatus()).isEqualTo("QUARANTINED");
        assertThat(proofs.findById(target().getProofId()).orElseThrow()
                .getStatus()).isEqualTo("QUARANTINED");
        assertAuthorityQuarantined(
                workers.findById(WORKER_ID).orElseThrow().getAvailability(),
                workers.findById(WORKER_ID).orElseThrow().getConflictState());
        assertAuthorityQuarantined(
                sessions.findById(SESSION_ID).orElseThrow().getAvailability(),
                sessions.findById(SESSION_ID).orElseThrow().getConflictState());
        assertAuthorityQuarantined(
                tasks.findById(TASK_ID).orElseThrow().getAvailability(),
                tasks.findById(TASK_ID).orElseThrow().getConflictState());
    }

    @Test
    void navigatorInstanceDriftCannotRenewAnotherInstancesProof() {
        prepareAuthorityAndReservation(TASK_ID, SESSION_ID);
        admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID));
        properties.setInstanceId("replacement-navigator-instance");

        assertThatThrownBy(authority::observeAndRenewConfiguredProof)
                .hasMessage(LifecycleActivationReason.INSTANCE_NOT_REGISTERED);

        assertThat(target().getStatus()).isEqualTo("QUARANTINED");
        assertThat(proofs.findById(target().getProofId()).orElseThrow()
                .getStatus()).isEqualTo("QUARANTINED");
        assertAuthorityQuarantined(
                workers.findById(WORKER_ID).orElseThrow().getAvailability(),
                workers.findById(WORKER_ID).orElseThrow().getConflictState());
    }

    @Test
    void unavailableWorkerQuarantinesAndStopsEveryNewEffect() {
        prepareAuthorityAndReservation(TASK_ID, SESSION_ID);
        admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID));
        fixture.workerAvailable = false;

        assertThatThrownBy(authority::observeAndRenewConfiguredProof)
                .hasMessage(LifecycleActivationReason.WORKER_NOT_READY);

        assertThat(target().getStatus()).isEqualTo("QUARANTINED");
        assertThatThrownBy(() -> admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID)))
                .hasMessage(LifecycleActivationReason.TARGET_NOT_READY);
        assertThat(outbox.findAll()).singleElement()
                .extracting(LifecycleEffectOutboxEntity::getEffectState)
                .isEqualTo("EFFECT_STARTED");
    }

    @Test
    void receiptShadowAndStaleProofNegativesRemainZeroEffect() {
        prepareAuthority();
        ReflectionTestUtils.setField(
                authority, "terminationReceiptEnabled", false);
        assertThatThrownBy(() -> admission.reserveProductionAdmission(
                request(TASK_ID, SESSION_ID, null,
                        "synthetic-arch001-tenant")))
                .hasMessage(LifecycleActivationReason.RECEIPT_REQUIRED);
        assertThat(outbox.count()).isZero();

        reset();
        prepareAuthorityAndReservation(TASK_ID, SESSION_ID);
        var worker = workers.findById(WORKER_ID).orElseThrow();
        worker.setOwnershipMode("ENFORCED");
        workers.saveAndFlush(worker);
        assertThatThrownBy(() -> admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID)))
                .hasMessage(LifecycleActivationReason.WORKER_IDENTITY_MISMATCH);
        assertThat(outbox.count()).isZero();
        assertThat(tasks.count()).isZero();

        reset();
        prepareAuthorityAndReservation(TASK_ID, SESSION_ID);
        fixture.now = fixture.now.plusSeconds(45);
        assertThatThrownBy(() -> admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID)))
                .hasMessage(LifecycleActivationReason.PROOF_NOT_ACTIVE);
        assertThat(outbox.count()).isZero();
        assertThat(references.count()).isZero();
    }

    @Test
    void workerBuildProtocolCapabilityAndAuthenticationAreServerObserved() {
        authority.registerConfiguredTarget(TARGET_ID);
        fixture.readiness = new WorkerLifecycleActivationReadiness(
                true, true, "NAVIGATOR_WORKER_LIFECYCLE_V1", 1,
                identity(), Set.of(), "source-candidate",
                true, true, true, List.of());
        assertThatThrownBy(() -> authority.acquireConfiguredProof(TARGET_ID))
                .hasMessage(LifecycleActivationReason.WORKER_CAPABILITY_MISMATCH);

        fixture.readiness = new WorkerLifecycleActivationReadiness(
                true, true, "NAVIGATOR_WORKER_LIFECYCLE_V1", 2,
                identity(), LifecycleActivationAuthorityService
                .REQUIRED_CAPABILITIES, "source-candidate",
                true, true, true, List.of());
        assertThatThrownBy(() -> authority.acquireConfiguredProof(TARGET_ID))
                .hasMessage(LifecycleActivationReason.WORKER_PROTOCOL_MISMATCH);

        fixture.readiness = new WorkerLifecycleActivationReadiness(
                true, true, "NAVIGATOR_WORKER_LIFECYCLE_V1", 1,
                identity(), LifecycleActivationAuthorityService
                .REQUIRED_CAPABILITIES, "different-build",
                true, true, true, List.of());
        assertThatThrownBy(() -> authority.acquireConfiguredProof(TARGET_ID))
                .hasMessage(LifecycleActivationReason.WORKER_BUILD_MISMATCH);

        fixture.readiness = new WorkerLifecycleActivationReadiness(
                true, true, "NAVIGATOR_WORKER_LIFECYCLE_V1", 1,
                identity(), LifecycleActivationAuthorityService
                .REQUIRED_CAPABILITIES, "source-candidate",
                true, false, true, List.of());
        assertThatThrownBy(() -> authority.acquireConfiguredProof(TARGET_ID))
                .hasMessage(LifecycleActivationReason.WORKER_NOT_READY);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void incompleteCapabilityManifestIsRejectedBeforeAnyWorkerObservation() {
        authority.registerConfiguredTarget(TARGET_ID);
        fixture.nullCapabilities = true;
        fixture.resolveCalls.set(0);

        assertThatThrownBy(() -> authority.acquireConfiguredProof(TARGET_ID))
                .hasMessage(LifecycleActivationReason
                        .WORKER_CAPABILITY_MISMATCH);

        assertThat(fixture.resolveCalls).hasValue(0);
        assertThat(target().getStatus()).isEqualTo("REGISTERED");
        assertThat(outbox.count()).isZero();
    }

    @Test
    void sharedOrWrongMysqlIdentityCannotRegisterActivationAuthority() {
        fixture.databaseIdentity = new LifecycleAuthorityClock.DatabaseIdentity(
                "MySQL", "8.0.44", "navigator", "127.0.0.1", 3306);

        assertThatThrownBy(() -> authority.registerConfiguredTarget(TARGET_ID))
                .hasMessage(LifecycleActivationReason.DATABASE_MISMATCH);
        assertThat(targets.count()).isZero();
        assertThat(generations.count()).isZero();
    }

    @Test
    void controllerInventoryClassAndEvidenceSourceAreServerFixed() {
        fixture.controllerContractDrift = true;

        assertThatThrownBy(() -> authority.registerConfiguredTarget(TARGET_ID))
                .hasMessage(LifecycleActivationReason
                        .CONTROLLER_INVENTORY_UNPROVEN);
        assertThat(targets.count()).isZero();
        assertThat(generations.count()).isZero();
    }

    @Test
    void lateControllerRelaunchAndLeaseExpiryAreMonotonicLosses() {
        prepareAuthorityAndReservation(TASK_ID, SESSION_ID);
        admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID));
        fixture.lateRelaunch = true;

        assertThatThrownBy(authority::observeAndRenewConfiguredProof)
                .hasMessage(LifecycleActivationReason.CONTROLLER_DRIFT);
        assertThat(target().getStatus()).isEqualTo("QUARANTINED");

        reset();
        prepareAuthorityAndReservation(TASK_ID, SESSION_ID);
        admission.admitAndAuthorizeProviderEffect(
                providerEffect(TASK_ID, SESSION_ID));
        fixture.now = fixture.now.plusSeconds(45);
        assertThatThrownBy(authority::observeAndRenewConfiguredProof)
                .hasMessage(LifecycleActivationReason.PROOF_NOT_ACTIVE);
        assertThat(target().getStatus()).isEqualTo("QUARANTINED");
    }

    @Test
    void proofRenewalUsesDatabaseClockAndIncrementsVersion() {
        prepareAuthority();
        long before = proofs.findById(target().getProofId())
                .orElseThrow().getProofVersion();
        fixture.now = fixture.now.plusSeconds(5);

        var renewed = authority.observeAndRenewConfiguredProof();

        assertThat(renewed.authorityReady()).isTrue();
        assertThat(proofs.findById(target().getProofId()).orElseThrow()
                .getProofVersion()).isEqualTo(before + 1);
        assertThat(proofs.findById(target().getProofId()).orElseThrow()
                .getLastVerifiedAt()).isEqualTo(fixture.now);
    }

    private void prepareAuthorityAndReservation(String taskId, String sessionId) {
        prepareAuthorityAndReservation(taskId, sessionId, false);
    }

    private void prepareAuthorityAndReservation(
            String taskId, String sessionId, boolean localDevelopmentTarget) {
        prepareAuthority();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            admission.reserveProductionAdmission(
                    request(taskId, sessionId, null,
                            "synthetic-arch001-tenant",
                            localDevelopmentTarget));
            canonicalTasks.save(canonical(
                    taskId, sessionId, localDevelopmentTarget));
        });
        assertThat(admission.ownershipModeForTask(taskId))
                .isEqualTo(LifecycleOwnershipMode.ENFORCED);
    }

    private void prepareAuthority() {
        authority.registerConfiguredTarget(TARGET_ID);
        var readiness = authority.acquireConfiguredProof(TARGET_ID);
        assertThat(readiness.authorityReady()).isTrue();
        assertThat(readiness.admissionGateOpen()).isTrue();
    }

    private LifecycleProductionAdmissionService.ProductionAdmissionRequest request(
            String taskId, String sessionId, String existingSessionId,
            String tenantId) {
        return request(taskId, sessionId, existingSessionId, tenantId, false);
    }

    private LifecycleProductionAdmissionService.ProductionAdmissionRequest request(
            String taskId, String sessionId, String existingSessionId,
            String tenantId, boolean localDevelopmentTarget) {
        return request(taskId, sessionId, existingSessionId, tenantId,
                localDevelopmentTarget, MODEL_CONFIG, MODEL);
    }

    private LifecycleProductionAdmissionService.ProductionAdmissionRequest request(
            String taskId, String sessionId, String existingSessionId,
            String tenantId, boolean localDevelopmentTarget,
            String modelConfigId, String model) {
        return new LifecycleProductionAdmissionService.ProductionAdmissionRequest(
                localDevelopmentTarget ? "codex-worker" : "codex-biz-worker",
                tenantId, "synthetic-arch001-user",
                WORKER_ID, sessionId, taskId, modelConfigId, model,
                existingSessionId, PROMPT_DIGEST,
                localDevelopmentTarget ? null : "synthetic/arch001/canary",
                "/tmp/" + RUN_ID + "/workdir",
                Map.of(), List.of(), false, "disabled", null);
    }

    private LifecycleProductionAdmissionService.ProviderEffectCommand providerEffect(
            String taskId, String sessionId) {
        return new LifecycleProductionAdmissionService.ProviderEffectCommand(
                taskId, sessionId, WORKER_ID, identity(), DISPATCH_ID,
                "JCS_SHA256_V1", BINDING_DIGEST);
    }

    private WorkerLifecycleIdentity identity() {
        return new WorkerLifecycleIdentity(
                WORKER_ID, STATE_GENERATION, INSTANCE_EPOCH);
    }

    private SessionTaskEntity canonical(String taskId, String sessionId) {
        return canonical(taskId, sessionId, false);
    }

    private SessionTaskEntity canonical(
            String taskId, String sessionId, boolean localDevelopmentTarget) {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId(sessionId);
        task.setProviderType(localDevelopmentTarget
                ? "codex-worker" : "codex-biz-worker");
        task.setWorkerId(WORKER_ID);
        task.setTenantId("synthetic-arch001-tenant");
        task.setUserId("synthetic-arch001-user");
        task.setModelConfigId(MODEL_CONFIG);
        task.setModel(MODEL);
        task.setStatus("RUNNING");
        task.setPrompt("not-inspected-by-lifecycle-authority");
        return task;
    }

    private com.foggy.navigator.session.lifecycle.persistence
            .LifecycleActivationTargetEntity target() {
        return targets.findById(TARGET_ID).orElseThrow();
    }

    private void assertAuthorityQuarantined(
            String availability, String conflict) {
        assertThat(availability).isEqualTo(
                LifecycleAvailability.AUTHORITY_QUARANTINED.name());
        assertThat(conflict).isEqualTo(LifecycleConflictState
                .LEGACY_WRITER_EXCLUSIVITY_LOST.name());
    }

    private String stableId(String prefix, String value) {
        try {
            return prefix + "-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 48);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    static final class MutableAuthorityFixture
            implements LifecycleActivationArtifactSource,
            LifecycleAuthorityClock,
            WorkerLifecyclePortResolver {
        private final LifecycleActivationProperties properties;
        private final ObjectMapper objectMapper;
        private LocalDateTime now;
        private WorkerLifecycleActivationReadiness readiness;
        private boolean lateRelaunch;
        private boolean workerAvailable;
        private boolean controllerContractDrift;
        private boolean nullCapabilities;
        private boolean localDevelopmentTarget;
        private String manifestModel;
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private DatabaseIdentity databaseIdentity;

        MutableAuthorityFixture(
                LifecycleActivationProperties properties,
                ObjectMapper objectMapper) {
            this.properties = properties;
            this.objectMapper = objectMapper;
            reset();
        }

        void reset() {
            now = LocalDateTime.now().withNano(0);
            readiness = readiness(new WorkerLifecycleIdentity(
                    WORKER_ID, STATE_GENERATION, INSTANCE_EPOCH));
            lateRelaunch = false;
            workerAvailable = true;
            controllerContractDrift = false;
            nullCapabilities = false;
            localDevelopmentTarget = false;
            manifestModel = MODEL;
            resolveCalls.set(0);
            databaseIdentity = new DatabaseIdentity(
                    "MySQL", "8.0.44", "arch001_act_run_fixture",
                    "127.0.0.1", 13306);
        }

        WorkerLifecycleActivationReadiness readiness(
                WorkerLifecycleIdentity identity) {
            return new WorkerLifecycleActivationReadiness(
                    true, true, "NAVIGATOR_WORKER_LIFECYCLE_V1", 1,
                    identity,
                    LifecycleActivationAuthorityService.REQUIRED_CAPABILITIES,
                    "source-candidate", true, true, true, List.of());
        }

        @Override
        public ActivationArtifacts load() {
            List<LifecycleActivationManifest.Controller> controllers = List.of(
                    controller("process", "target-process-set", "DISABLED"),
                    controller("supervisor", "none", "NOT_APPLICABLE"),
                    controller("manual_launcher", "target-pidfiles", "DISABLED"),
                    controller("ci", "none", "NOT_APPLICABLE"),
                    controller("timer", "none", "NOT_APPLICABLE"),
                    controller("docker", "mysql-compose", "DISABLED"));
            FileLifecycleActivationArtifactSource digester =
                    new FileLifecycleActivationArtifactSource(
                            properties, objectMapper);
            String controllerDigest =
                    digester.controllerInventoryDigest(controllers);
            LifecycleActivationManifest manifest =
                    new LifecycleActivationManifest(
                            LifecycleActivationAuthorityService.MANIFEST_SCHEMA,
                            TARGET_ID, RUN_ID,
                            localDevelopmentTarget
                                    ? LifecycleActivationAuthorityService
                                    .LOCAL_DEVELOPMENT_TARGET_CLASS
                                    : LifecycleActivationAuthorityService
                                    .TARGET_CLASS,
                            LifecycleActivationAuthorityService.PROVIDER_LANE,
                            new LifecycleActivationManifest.Candidate(
                                    properties.getCandidateHead(),
                                    properties.getCandidatePatchSha256(), 1),
                            new LifecycleActivationManifest.ExactTuple(
                                    localDevelopmentTarget
                                            ? "codex-worker"
                                            : "codex-biz-worker",
                                    "synthetic-arch001-tenant",
                                    "synthetic-arch001-user", WORKER_ID,
                                    MODEL_CONFIG, manifestModel,
                                    localDevelopmentTarget
                                            ? null
                                            : "synthetic/arch001/canary",
                                    PROMPT_DIGEST),
                            new LifecycleActivationManifest.Target(
                                    "127.0.0.1", 18112, 13051, 13306, "8.0.44",
                                    "arch001_act_run_fixture", RUN_ID,
                                    "/tmp/" + RUN_ID,
                                    "/tmp/" + RUN_ID + "/workdir",
                                    "/tmp/" + RUN_ID + "/worker-home",
                                    "/tmp/" + RUN_ID + "/provider.env",
                                    "/tmp/" + RUN_ID + "/worker.env",
                                    "/tmp/" + RUN_ID + "/runtime.env",
                                    "/tmp/" + RUN_ID + "/database.env",
                                    "/tmp/" + RUN_ID + "/control.env",
                                    "/tmp/" + RUN_ID + "/compose.yml",
                                    "/tmp/" + RUN_ID + "/evidence",
                                    "/tmp/" + RUN_ID + "/navigator.pid",
                                    "/tmp/" + RUN_ID + "/worker.pid",
                                    "/tmp/" + RUN_ID + "/controller-observation.json"),
                            new LifecycleActivationManifest.Worker(
                                    "source-candidate", 1,
                                    nullCapabilities ? null : List.copyOf(
                                            LifecycleActivationAuthorityService
                                                    .REQUIRED_CAPABILITIES)),
                            controllers, controllerDigest);
            String manifestDigest = "fixture-manifest-digest";
            Instant observedAt = now.toInstant(ZoneOffset.UTC);
            return new ActivationArtifacts(
                    manifest, manifestDigest,
                    new LifecycleActivationManifest.ControllerObservation(
                            LifecycleActivationAuthorityService.OBSERVATION_SCHEMA,
                            TARGET_ID, RUN_ID, controllerDigest,
                            manifestDigest, observedAt, true,
                            lateRelaunch, lateRelaunch ? 1 : 0,
                            "LIVE_LOCAL_INSPECTION"));
        }

        @Override
        public LocalDateTime databaseNow() {
            return now;
        }

        @Override
        public DatabaseIdentity databaseIdentity() {
            return databaseIdentity;
        }

        @Override
        public Optional<WorkerLifecyclePort> resolve(String physicalWorkerId) {
            resolveCalls.incrementAndGet();
            if (!workerAvailable || !WORKER_ID.equals(physicalWorkerId)) {
                return Optional.empty();
            }
            return Optional.of(new WorkerLifecyclePort() {
                @Override
                public WorkerLifecycleReadiness probe(String workerId) {
                    return new WorkerLifecycleReadiness(
                            readiness.workerReady(), readiness.identity(),
                            readiness.capabilities(), readiness.reasonCodes());
                }

                @Override
                public WorkerLifecycleActivationReadiness activationReadiness(
                        String workerId) {
                    return readiness;
                }

                @Override
                public WorkerLifecycleSnapshot inventory(
                        WorkerLifecycleIdentity expectedIdentity,
                        long afterSequence) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public long acknowledge(
                        WorkerLifecycleIdentity expectedIdentity,
                        long throughSequence) {
                    throw new UnsupportedOperationException();
                }
            });
        }

        private LifecycleActivationManifest.Controller controller(
                String kind, String id, String state) {
            String source = switch (kind) {
                case "process" -> "proc-cwd-scan";
                case "supervisor" -> "local-target-no-supervisor";
                case "manual_launcher" -> "target-pidfile-scan";
                case "ci" -> "local-target-no-ci";
                case "timer" -> "local-target-no-timer";
                case "docker" -> "compose-label-scan";
                default -> throw new IllegalArgumentException(kind);
            };
            if (controllerContractDrift && "process".equals(kind)) {
                source = "caller-asserted-process-state";
            }
            return new LifecycleActivationManifest.Controller(
                    kind, id, state, "NONE", RUN_ID, source,
                    properties.getCandidateHead(), "/tmp/" + RUN_ID);
        }
    }
}
