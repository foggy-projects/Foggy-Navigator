package com.foggy.navigator.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.config.BusinessTaskScopedTokenProperties;
import com.foggy.navigator.business.agent.lifecycle.BusinessTaskTerminalTombstoneParticipant;
import com.foggy.navigator.business.agent.lifecycle.BusinessTerminalCleanupPort;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskTerminalStateRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.business.agent.repository.RuntimeRequestAuditRepository;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskScopedTokenRuntimeStore;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenLifecycleService;
import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenPolicyService;
import com.foggy.navigator.business.agent.service.ClientAppRuntimeCredentialResolver;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditProperties;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO;
import com.foggy.navigator.claude.worker.model.dto.RuntimeTaskFactsDTO;
import com.foggy.navigator.claude.worker.service.RuntimeStateAuditService;
import com.foggy.navigator.claude.worker.service.RuntimeTaskClosureService;
import com.foggy.navigator.claude.worker.service.RuntimeTerminationAcceptanceCoordinator;
import com.foggy.navigator.claude.worker.service.RuntimeTerminationOutboxDispatcher;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.lifecycle.CodexLifecycleBindingDigest;
import com.foggy.navigator.codex.worker.lifecycle.CodexWorkerLifecyclePortResolver;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.codex.worker.service.CodexStreamRelay;
import com.foggy.navigator.codex.worker.service.CodexTaskRuntimeStateService;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.codex.worker.spi.CodexWorkerFacadeImpl;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.TerminationOperationEntity;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.lifecycle.*;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleFactEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleEffectOutboxEntity;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleWriterProofEntity;
import com.foggy.navigator.session.lifecycle.persistence.SessionLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.*;
import com.foggy.navigator.session.repository.TerminationOperationRepository;
import com.foggy.navigator.session.service.TerminationOperationService;
import com.foggy.navigator.spi.lifecycle.LifecycleOwnershipMode;
import com.foggy.navigator.spi.lifecycle.TaskLifecycleProjectionPort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleTask;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(Arch001ThirdRemediationSlice8IntegrationTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class Arch001ThirdRemediationSlice8IntegrationTest {
    private static final String TASK_ID = "arch001-slice8-task";
    private static final String SESSION_ID = "arch001-slice8-session";
    private static final String WORKER_ID = "arch001-java-node-worker";
    private static final String TOKEN = "arch001-java-node-fixture-token";
    private static final String REQUEST_ID =
            "31111111-2222-4333-8444-555555555555";

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EntityScan(basePackageClasses = {
            SessionTaskEntity.class,
            LifecycleFactEntity.class,
            BusinessTaskScopedTokenEntity.class,
            CodexTaskEntity.class,
            TerminationOperationEntity.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            SessionTaskRepository.class,
            LifecycleFactRepository.class,
            BusinessTaskScopedTokenRepository.class,
            CodexTaskRepository.class,
            TerminationOperationRepository.class
    })
    @Import({
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            TaskLifecycleOwnerService.class,
            TaskTerminalCommitService.class,
            TerminalCleanupHandler.class,
            TerminalCleanupFinalizer.class,
            TerminalCleanupStepExecutor.class,
            CompatibilityTaskProjectionCleanupAction.class,
            PhysicalTokenCleanupAction.class,
            TerminationReceiptCleanupAction.class,
            SessionForegroundLaneService.class,
            TaskLifecycleProjectionService.class,
            WriterExclusivityProofService.class,
            LifecycleEnrollmentRetirementService.class,
            TaskTerminationIntentRecorder.class,
            LifecycleEnrollmentService.class,
            WorkerLifecycleReconciliationCommitService.class,
            JpaSentinelLeaseStore.class,
            WorkerLifecycleSentinelService.class,
            WorkerLifecycleSentinelScheduler.class,
            RuntimeTerminationAcceptanceCoordinator.class,
            RuntimeTerminationOutboxDispatcher.class,
            RuntimeTaskClosureService.class,
            RuntimeRequestAuditService.class,
            BusinessTaskScopedTokenLifecycleService.class,
            BusinessTaskScopedTokenPolicyService.class,
            BusinessAgentTaskScopedTokenRuntimeStore.class,
            BusinessTaskTerminalTombstoneParticipant.class,
            BusinessTerminalCleanupPort.class,
            TerminationOperationService.class,
            CodexWorkerClientFactory.class,
            CodexLifecycleBindingDigest.class,
            CodexTaskService.class,
            CodexWorkerFacadeImpl.class,
            CodexWorkerLifecyclePortResolver.class
    })
    static class Config {
        @Bean(destroyMethod = "close")
        NodeFixture nodeFixture() throws Exception {
            return NodeFixture.start();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        WorkerManagementFacade workerManagementFacade(NodeFixture node) {
            return new FixtureWorkerManagement(node);
        }

        @Bean
        CodexTaskRuntimeStateService codexTaskRuntimeStateService() {
            return mock(CodexTaskRuntimeStateService.class);
        }

        @Bean
        CodexStreamRelay codexStreamRelay() {
            return mock(CodexStreamRelay.class);
        }

        @Bean
        RuntimeStateAuditService runtimeStateAuditService() {
            return mock(RuntimeStateAuditService.class);
        }

        @Bean
        TerminalCleanupPlanFactory terminalCleanupPlanFactory() {
            return new TerminalCleanupPlanFactory();
        }

        @Bean
        BusinessTaskScopedTokenProperties tokenProperties() {
            return new BusinessTaskScopedTokenProperties();
        }

        @Bean
        RuntimeRequestAuditProperties runtimeRequestAuditProperties() {
            return new RuntimeRequestAuditProperties();
        }

        @Bean
        ClientAppRuntimeCredentialResolver credentialResolver() {
            ClientAppRuntimeCredentialResolver resolver =
                    mock(ClientAppRuntimeCredentialResolver.class);
            when(resolver.resolve(anyString(), anyString()))
                    .thenReturn(Optional.of(
                            ResolvedClientAppCredentialDTO.builder()
                                    .credentialId("slice8-credential")
                                    .tenantId("slice8-tenant")
                                    .clientAppId("slice8-client")
                                    .build()));
            return resolver;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired NodeFixture node;
    @org.springframework.beans.factory.annotation.Autowired CodexTaskRepository codexTasks;
    @org.springframework.beans.factory.annotation.Autowired SessionTaskRepository sessionTasks;
    @org.springframework.beans.factory.annotation.Autowired LifecycleWriterProofRepository proofs;
    @org.springframework.beans.factory.annotation.Autowired LifecycleEnrollmentService enrollment;
    @org.springframework.beans.factory.annotation.Autowired WorkerLifecycleReconciliationCommitService reconciliation;
    @org.springframework.beans.factory.annotation.Autowired SessionLifecycleSnapshotRepository sessions;
    @org.springframework.beans.factory.annotation.Autowired LifecycleEffectOutboxRepository outbox;
    @org.springframework.beans.factory.annotation.Autowired TaskLifecycleSnapshotRepository snapshots;
    @org.springframework.beans.factory.annotation.Autowired WorkerLifecycleSentinelScheduler scheduler;
    @org.springframework.beans.factory.annotation.Autowired TaskLifecycleProjectionPort projection;
    @org.springframework.beans.factory.annotation.Autowired RuntimeTaskClosureService closure;
    @org.springframework.beans.factory.annotation.Autowired RuntimeStateAuditService stateAudit;
    @org.springframework.beans.factory.annotation.Autowired ClientAppRepository clientApps;
    @org.springframework.beans.factory.annotation.Autowired BusinessTaskScopedTokenLifecycleService tokens;
    @org.springframework.beans.factory.annotation.Autowired BusinessTaskScopedTokenRepository tokenRepository;
    @org.springframework.beans.factory.annotation.Autowired BusinessTaskTerminalStateRepository terminalStates;
    @org.springframework.beans.factory.annotation.Autowired RuntimeTerminationAcceptanceCoordinator acceptance;
    @org.springframework.beans.factory.annotation.Autowired RuntimeTerminationOutboxDispatcher dispatcher;
    @org.springframework.beans.factory.annotation.Autowired CodexWorkerFacadeImpl codexProvider;
    @org.springframework.beans.factory.annotation.Autowired WriterExclusivityProofService writerProofs;
    @org.springframework.beans.factory.annotation.Autowired WorkerLifecycleSnapshotRepository workerSnapshots;
    @org.springframework.beans.factory.annotation.Autowired PlatformTransactionManager transactionManager;

    private String providerTaskId;
    private NodeStartedTask startedTask;

    @BeforeEach
    void setUp() {
        startedTask = startRealNodeTask();
        providerTaskId = startedTask.providerTaskId();
        WorkerLifecycleIdentity identity = new WorkerLifecycleIdentity(
                WORKER_ID, node.stateGeneration, node.instanceEpoch);
        reconciliation.commit(new WorkerLifecycleSnapshot(
                identity, 0, 0, true, List.of(), List.of()), null);

        CodexTaskEntity codex = new CodexTaskEntity();
        codex.setTaskId(TASK_ID);
        codex.setWorkerTaskId(providerTaskId);
        codex.setSessionId(SESSION_ID);
        codex.setWorkerId(WORKER_ID);
        codex.setUserId("slice8-owner");
        codex.setTenantId("slice8-tenant");
        codex.setProviderType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        codex.setStatus("RUNNING");
        codex.setPrompt("ARCH001_HOLD_FOR_ABORT");
        codex.setCwd(node.worker.toString());
        codexTasks.saveAndFlush(codex);

        SessionTaskEntity canonical = new SessionTaskEntity();
        canonical.setTaskId(TASK_ID);
        canonical.setSessionId(SESSION_ID);
        canonical.setProviderType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        canonical.setProviderTaskId(providerTaskId);
        canonical.setWorkerId(WORKER_ID);
        canonical.setUserId("slice8-owner");
        canonical.setTenantId("slice8-tenant");
        canonical.setAgentId(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        canonical.setStatus("RUNNING");
        sessionTasks.saveAndFlush(canonical);

        LocalDateTime now = LocalDateTime.now();
        LifecycleWriterProofEntity proof = new LifecycleWriterProofEntity();
        proof.setProofId("slice8-proof");
        proof.setGenerationId("slice8-writer-generation");
        proof.setControllerInventoryDigest("slice8-controller-inventory");
        proof.setHolderInstanceId("slice8-fixture-holder");
        proof.setProofVersion(1);
        proof.setStatus("ACTIVE");
        proof.setAcquiredAt(now);
        proof.setLastVerifiedAt(now);
        proof.setExpiresAt(now.plusMinutes(5));
        proofs.saveAndFlush(proof);
        enrollment.enroll(new LifecycleEnrollmentService.EnrollmentCommand(
                enrollmentRequest(now), identity, SESSION_ID,
                new WorkerLifecycleTask(
                        TASK_ID, providerTaskId, LifecycleOwnershipMode.SHADOW,
                        startedTask.dispatchId(), "JCS_SHA256_V1",
                        startedTask.bindingDigest(), "EFFECT_STARTED", 1),
                "slice8-proof", "slice8-writer-generation"));

        SessionLifecycleSnapshotEntity lane = sessions.findById(SESSION_ID)
                .orElseThrow();
        lane.setForegroundTaskId(TASK_ID);
        lane.setForegroundLaneState("OCCUPIED");
        sessions.saveAndFlush(lane);
        createBusinessResources();
        stubPublicAudit();
    }

    @Test
    void publicClosureThroughRealNodeAndScheduledSentinelCompletesSlice8() {
        var result = closure.terminate(
                "slice8-key", "slice8-secret", "slice8-upstream",
                REQUEST_ID, TASK_ID, WORKER_ID,
                "operator-stuck-task-termination", TASK_ID, false);
        assertThat(result.getTerminationDispatched()).isTrue();

        var parent = outbox.findByIdempotencyKey(
                "termination-intent:" + REQUEST_ID).orElseThrow();
        assertThat(parent.getEffectState()).isEqualTo("RESULT_OBSERVED");
        assertThat(parent.getOwnershipMode()).isEqualTo("ENFORCED");
        assertThat(parent.getStateGeneration()).isEqualTo(node.stateGeneration);
        assertThat(parent.getInstanceEpoch()).isEqualTo(node.instanceEpoch);
        assertThat(parent.getBindingDigestVersion()).isEqualTo("JCS_SHA256_V1");
        assertThat(outbox.findByAggregateIdAndOperationId(
                        TASK_ID, parent.getOperationId()))
                .filteredOn(effect ->
                        "WORKER_LIFECYCLE_COMMAND".equals(effect.getEffectType()))
                .singleElement()
                .satisfies(effect -> {
                    assertThat(effect.getEffectState()).isEqualTo("EFFECT_STARTED");
                    assertThat(effect.getDispatchId()).isEqualTo(parent.getDispatchId());
                    assertThat(effect.getBindingDigest())
                            .isEqualTo(parent.getBindingDigest());
                });

        awaitNodeResult(parent);
        scheduler.reconcileEnrolledWorkers();

        assertThat(projection.find(TASK_ID)).hasValueSatisfying(value -> {
            assertThat(value.canonicalTerminal()).isTrue();
            assertThat(value.cleanupComplete()).isTrue();
            assertThat(value.typedTerminal()).isTrue();
            assertThat(value.terminalOutcome()).isEqualTo("CANCELLED");
        });
        assertThat(sessions.findById(SESSION_ID).orElseThrow()
                .getForegroundLaneState()).isEqualTo("FREE");
        assertThat(tokenRepository.findByTokenId("slice8-token")
                .orElseThrow().getStatus())
                .isEqualTo(BusinessAgentTaskService.STATUS_REVOKED);
        assertThat(terminalStates.findByTenantIdAndWorkerTaskId(
                "slice8-tenant", providerTaskId)).isPresent();
    }

    @Test
    void lossFirstExecutesRepositoryDispatcherAndNeverReachesNodeProviderRoute()
            throws Exception {
        String requestId = "41111111-2222-4333-8444-555555555555";
        acceptPrepared(requestId);
        var delivery = outbox.findByIdempotencyKey(
                "termination-intent:" + requestId).orElseThrow();
        CountDownLatch proofLocked = new CountDownLatch(1);
        CountDownLatch dispatcherStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var loss = executor.submit(() -> new TransactionTemplate(
                    transactionManager).executeWithoutResult(status -> {
                LifecycleWriterProofEntity proof = proofs.findForUpdate(
                        "slice8-proof").orElseThrow();
                proof.setStatus("QUARANTINING");
                proofs.save(proof);
                proofLocked.countDown();
                await(dispatcherStarted);
            }));
            var dispatch = executor.submit(() -> {
                await(proofLocked);
                dispatcherStarted.countDown();
                return dispatcher.dispatch(requestId, "loss-first");
            });
            loss.get(10, TimeUnit.SECONDS);
            assertThat(dispatch.get(10, TimeUnit.SECONDS)).isNull();
        } finally {
            executor.shutdownNow();
        }

        writerProofs.resumeQuarantines();
        assertThat(proofs.findById("slice8-proof").orElseThrow().getStatus())
                .isEqualTo("QUARANTINED");
        assertThat(workerSnapshots.findById(WORKER_ID).orElseThrow()
                .getConflictState()).isEqualTo(
                LifecycleConflictState.LEGACY_WRITER_EXCLUSIVITY_LOST.name());
        assertThat(nodeInventoryDispatches())
                .noneMatch(disposition -> delivery.getDispatchId().equals(
                        disposition.get("dispatch_id")));
        assertThat(outbox.findById(delivery.getEffectId()).orElseThrow()
                .getEffectState()).isIn("PREPARED", "CLAIMED");
    }

    @Test
    void authorizationFirstExecutesOneNodeEffectAndQuarantineBlocksRedelivery()
            throws Exception {
        String requestId = "51111111-2222-4333-8444-555555555555";
        acceptPrepared(requestId);
        var delivery = outbox.findByIdempotencyKey(
                "termination-intent:" + requestId).orElseThrow();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var dispatch = executor.submit(() ->
                    dispatcher.dispatch(requestId, "authorization-first"));
            awaitProviderEffectStarted(delivery);
            var loss = executor.submit(() -> writerProofs.quarantine(
                    "slice8-proof"));
            assertThat(dispatch.get(15, TimeUnit.SECONDS)).isNotNull();
            loss.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        awaitNodeResult(delivery);
        List<Map<String, Object>> firstInventory = nodeInventoryDispatches();
        assertThat(firstInventory)
                .filteredOn(disposition -> delivery.getDispatchId().equals(
                        disposition.get("dispatch_id")))
                .singleElement()
                .satisfies(disposition -> {
                    assertThat(disposition.get("provider_effect_started"))
                            .isEqualTo(true);
                    assertThat(disposition.get("effect_phase"))
                            .isEqualTo("RESULT_OBSERVED");
                });

        assertThat(dispatcher.dispatch(requestId, "forbidden-redelivery"))
                .isNull();
        List<Map<String, Object>> replayInventory = nodeInventoryDispatches();
        assertThat(replayInventory.stream()
                .filter(disposition -> delivery.getDispatchId().equals(
                        disposition.get("dispatch_id"))).count()).isEqualTo(1);
        assertThat(proofs.findById("slice8-proof").orElseThrow().getStatus())
                .isEqualTo("QUARANTINED");
    }

    @Test
    void dispatcherRejectsProviderNetworkInsideDatabaseTransaction() {
        String requestId = "61111111-2222-4333-8444-555555555555";
        acceptPrepared(requestId);
        var delivery = outbox.findByIdempotencyKey(
                "termination-intent:" + requestId).orElseThrow();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status ->
                        dispatcher.dispatch(requestId, "transaction-guard")))
                .hasMessage(
                        "PROVIDER_CALL_INSIDE_DATABASE_TRANSACTION");

        assertThat(nodeInventoryDispatches())
                .noneMatch(disposition -> delivery.getDispatchId().equals(
                        disposition.get("dispatch_id")));
        assertThat(outbox.findById(delivery.getEffectId()).orElseThrow()
                .getEffectState()).isEqualTo("PREPARED");
    }

    private void acceptPrepared(String requestId) {
        acceptance.accept(
                requestId, "slice8-key", "slice8-secret",
                "slice8-upstream", TASK_ID, SESSION_ID,
                CodexTaskService.CODEX_BIZ_PROVIDER_TYPE, WORKER_ID,
                providerTaskId, "slice8-owner", "slice8-tenant",
                "fixture-termination", codexProvider);
        assertThat(outbox.findByIdempotencyKey(
                "termination-intent:" + requestId)).hasValueSatisfying(
                effect -> assertThat(effect.getEffectState())
                        .isEqualTo("PREPARED"));
    }

    private void awaitProviderEffectStarted(
            LifecycleEffectOutboxEntity delivery) {
        for (int attempt = 0; attempt < 200; attempt++) {
            boolean started = outbox.findByAggregateIdAndOperationId(
                            TASK_ID, delivery.getOperationId()).stream()
                    .filter(effect -> "WORKER_LIFECYCLE_COMMAND".equals(
                            effect.getEffectType()))
                    .anyMatch(effect -> Set.of(
                            "EFFECT_STARTED", "RESULT_OBSERVED", "COMPLETED")
                            .contains(effect.getEffectState()));
            if (started) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        throw new AssertionError(
                "provider command never committed EFFECT_STARTED");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodeInventoryDispatches() {
        // The provider-neutral SPI intentionally omits raw disposition rows;
        // use the production Java client against the mounted production
        // lifecycle route for the decisive durable Node inventory evidence.
        List<Map<String, Object>> dispatches = new CodexWorkerClient(node.baseUrl, TOKEN)
                .lifecycleInventory(WORKER_ID, node.stateGeneration, 0)
                .map(body -> (List<Map<String, Object>>) body.get("dispatches"))
                .block(Duration.ofSeconds(10));
        return dispatches == null ? List.of() : dispatches;
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

    private NodeStartedTask startRealNodeTask() {
        CodexWorkerClient client = new CodexWorkerClient(node.baseUrl, TOKEN);
        Map<String, Object> context = client.lifecycleContext(
                WORKER_ID, "ENFORCED", "TASK_CREATE", TASK_ID,
                "slice8-create-dispatch", 1, null)
                .block(Duration.ofSeconds(10));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", "ARCH001_HOLD_FOR_ABORT");
        body.put("cwd", node.worker.toString());
        body.put("model", "gpt-5.6-sol");
        body.put("lifecycle_context", context);
        ServerSentEvent<String> event = client.streamQuery(body)
                .filter(value -> "lifecycle_disposition".equals(value.event()))
                .next().block(Duration.ofSeconds(15));
        try {
            Map<?, ?> disposition = new ObjectMapper().readValue(
                    event.data(), Map.class);
            assertThat(disposition.get("effect_phase"))
                    .isEqualTo("EFFECT_STARTED");
            return new NodeStartedTask(
                    String.valueOf(disposition.get("provider_task_id")),
                    String.valueOf(disposition.get("dispatch_id")),
                    String.valueOf(
                            disposition.get("safe_binding_digest")));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private record NodeStartedTask(
            String providerTaskId,
            String dispatchId,
            String bindingDigest) {
    }

    private void awaitNodeResult(
            com.foggy.navigator.session.lifecycle.persistence
                    .LifecycleEffectOutboxEntity parent) {
        var port = new CodexWorkerLifecyclePortResolver(
                new FixtureWorkerManagement(node),
                new ObjectMapper().findAndRegisterModules())
                .resolve(WORKER_ID).orElseThrow();
        WorkerLifecycleIdentity identity = new WorkerLifecycleIdentity(
                WORKER_ID, node.stateGeneration, node.instanceEpoch);
        for (int attempt = 0; attempt < 100; attempt++) {
            var status = port.dispatchStatus(
                    identity, LifecycleOwnershipMode.ENFORCED,
                    parent.getDispatchId(), parent.getBindingDigestVersion(),
                    parent.getBindingDigest());
            if ("RESULT_OBSERVED".equals(status.effectPhase())) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        throw new AssertionError("real Node abort did not reach RESULT_OBSERVED");
    }

    private LifecycleEnrollmentGate.EnrollmentRequest enrollmentRequest(
            LocalDateTime now) {
        return new LifecycleEnrollmentGate.EnrollmentRequest(
                CodexTaskService.CODEX_BIZ_PROVIDER_TYPE,
                true, true, false, true, true, true, true, true, true,
                Set.of(
                        "AUTHENTICATED_LIFECYCLE_V1",
                        "FENCED_INVENTORY_V1",
                        "DURABLE_LIFECYCLE_FACTS_V1",
                        "MONOTONIC_ACK_V1",
                        "EXACT_DISPATCH_DEDUPE_V1",
                        "DURABLE_PROVIDER_TASK_ID_V1",
                        "TERMINATION_ATOMIC_CAPABILITY_V1"),
                true, now.plusMinutes(5), now);
    }

    private void createBusinessResources() {
        ClientAppEntity app = new ClientAppEntity();
        app.setClientAppId("slice8-client");
        app.setTenantId("slice8-tenant");
        app.setName("slice8 fixture");
        app.setUpstreamSystemId("slice8-upstream");
        app.setStatus("ENABLED");
        clientApps.saveAndFlush(app);

        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setTokenId("slice8-token");
        token.setTaskId(TASK_ID);
        token.setSessionId(SESSION_ID);
        token.setTenantId("slice8-tenant");
        token.setClientAppId("slice8-client");
        token.setUpstreamUserId("slice8-upstream");
        token.setNavigatorEffectiveUserId("slice8-owner");
        token.setSkillId("slice8-skill");
        token.setWorkerPoolId("slice8-pool");
        token.setModelConfigId("slice8-model");
        token.setStatus(BusinessAgentTaskService.STATUS_ACTIVE);
        tokens.issueNewToken(token, "slice8-token-value");
        tokens.bindIssuedTokenToWorkerTask(
                "slice8-tenant", "slice8-token", "slice8-token-value",
                providerTaskId, "slice8-provider-session", WORKER_ID);
    }

    private void stubPublicAudit() {
        when(stateAudit.requireOwnedTask(
                "slice8-key", "slice8-secret", "slice8-upstream", TASK_ID))
                .thenReturn(new RuntimeStateAuditService.OwnedRuntimeTask(
                        TASK_ID, SESSION_ID, providerTaskId, "slice8-owner",
                        "slice8-tenant", CodexTaskService.CODEX_BIZ_PROVIDER_TYPE,
                        WORKER_ID, "RUNNING", false, 1));
        RuntimeTaskFactsDTO facts = RuntimeTaskFactsDTO.builder()
                .taskId(TASK_ID)
                .terminal(false)
                .status("RUNNING")
                .physicalWorkerId(WORKER_ID)
                .taskTokenStatus("ACTIVE")
                .activeTaskRegistrationPresent(true)
                .dispatchCount(1)
                .retryCount(0)
                .recoveryCount(0)
                .requestedToolCount(0)
                .effectiveToolCount(0)
                .requestedFunctionCount(0)
                .effectiveFunctionCount(0)
                .taskTokenFunctionScopeEmpty(true)
                .runtimeDispatched(true)
                .modelDispatched(true)
                .businessFunctionDispatched(false)
                .build();
        when(stateAudit.auditTask(
                "slice8-key", "slice8-secret", "slice8-upstream", TASK_ID))
                .thenReturn(RuntimeTaskAuditDTO.builder()
                        .taskFacts(facts).status("RUNNING")
                        .terminal(false).build());
    }

    static final class NodeFixture implements AutoCloseable {
        private final Process process;
        private final Path worker;
        private final String baseUrl;
        private final String stateGeneration;
        private final String instanceEpoch;

        private NodeFixture(
                Process process, Path worker, String baseUrl,
                String stateGeneration, String instanceEpoch) {
            this.process = process;
            this.worker = worker;
            this.baseUrl = baseUrl;
            this.stateGeneration = stateGeneration;
            this.instanceEpoch = instanceEpoch;
        }

        static NodeFixture start() throws Exception {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !java.nio.file.Files.exists(
                    root.resolve("tools/codex-agent-worker/package.json"))) {
                root = root.getParent();
            }
            if (root == null) throw new IllegalStateException(
                    "FOGGY_NAVIGATOR_ROOT_NOT_FOUND");
            Path worker = root.resolve("tools/codex-agent-worker");
            Process process = new ProcessBuilder(
                    "node", "--import", "tsx",
                    "tests/fixtures/lifecycle-router-server.ts")
                    .directory(worker.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            BufferedReader reader = process.inputReader();
            String line = reader.readLine();
            Map<?, ?> startup = new ObjectMapper().readValue(line, Map.class);
            return new NodeFixture(
                    process, worker,
                    String.valueOf(startup.get("baseUrl")),
                    String.valueOf(startup.get("stateGeneration")),
                    String.valueOf(startup.get("instanceEpoch")));
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    static final class FixtureWorkerManagement
            implements WorkerManagementFacade {
        private final NodeFixture node;

        private FixtureWorkerManagement(NodeFixture node) {
            this.node = node;
        }

        @Override
        public CodexConfig getCodexConfig(String workerId) {
            if (!WORKER_ID.equals(workerId)) return null;
            return CodexConfig.builder()
                    .baseUrl(node.baseUrl)
                    .authToken(TOKEN)
                    .model("gpt-5.6-sol")
                    .build();
        }

        @Override
        public Set<String> listConfiguredCodexLifecycleWorkerIds() {
            return Set.of(WORKER_ID);
        }

        @Override
        public List<Map<String, Object>> listWorkers(String userId) {
            return List.of();
        }

        @Override
        public Map<String, Object> getWorker(String userId, String workerId) {
            return Map.of("id", workerId);
        }

        @Override
        public void validatePhysicalWorkerOwnership(
                String userId, String workerId) {
        }

        @Override
        public String initDirectory(
                String userId, String workerId, String path,
                Map<String, String> files) {
            throw new UnsupportedOperationException();
        }
    }
}
