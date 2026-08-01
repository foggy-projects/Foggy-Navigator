package com.foggy.navigator.claude.worker.service;

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
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditProperties;
import com.foggy.navigator.business.agent.service.ClientAppRuntimeCredentialResolver;
import com.foggy.navigator.claude.worker.model.enums.RuntimeTaskReconciliationState;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.lifecycle.*;
import com.foggy.navigator.session.lifecycle.persistence.LifecycleFactEntity;
import com.foggy.navigator.session.lifecycle.persistence.SessionLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.*;
import com.foggy.navigator.spi.lifecycle.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

@SpringJUnitConfig(
        BusinessLifecycleTerminalVerticalIntegrationTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class BusinessLifecycleTerminalVerticalIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    @EntityScan(basePackageClasses = {
            SessionTaskEntity.class,
            LifecycleFactEntity.class,
            BusinessTaskScopedTokenEntity.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            SessionTaskRepository.class,
            LifecycleFactRepository.class,
            BusinessTaskScopedTokenRepository.class
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
            RuntimeTerminationAcceptanceCoordinator.class,
            RuntimeTerminationOutboxDispatcher.class,
            RuntimeTaskClosureService.class,
            RuntimeRequestAuditService.class,
            BusinessTaskScopedTokenLifecycleService.class,
            BusinessTaskScopedTokenPolicyService.class,
            BusinessAgentTaskScopedTokenRuntimeStore.class,
            BusinessTaskTerminalTombstoneParticipant.class,
            BusinessTerminalCleanupPort.class
    })
    static class Config {
        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean TerminalCleanupPlanFactory terminalCleanupPlanFactory() {
            return new TerminalCleanupPlanFactory();
        }

        @Bean BusinessTaskScopedTokenProperties tokenProperties() {
            return new BusinessTaskScopedTokenProperties();
        }

        @Bean RuntimeRequestAuditProperties runtimeRequestAuditProperties() {
            return new RuntimeRequestAuditProperties();
        }

        @Bean ClientAppRuntimeCredentialResolver credentialResolver() {
            ClientAppRuntimeCredentialResolver resolver =
                    mock(ClientAppRuntimeCredentialResolver.class);
            when(resolver.resolve(anyString(), anyString()))
                    .thenReturn(Optional.of(
                            ResolvedClientAppCredentialDTO.builder()
                                    .credentialId("credential-business")
                                    .tenantId("tenant-business")
                                    .clientAppId("client-business")
                                    .build()));
            return resolver;
        }

        @Bean RuntimeStateAuditService runtimeStateAuditService() {
            return mock(RuntimeStateAuditService.class);
        }

        @Bean ProviderFixture providerFixture() {
            return new ProviderFixture();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    TaskLifecycleOwnerService owner;
    @org.springframework.beans.factory.annotation.Autowired
    SessionTaskRepository tasks;
    @org.springframework.beans.factory.annotation.Autowired
    SessionLifecycleSnapshotRepository sessions;
    @org.springframework.beans.factory.annotation.Autowired
    TaskLifecycleSnapshotRepository snapshots;
    @org.springframework.beans.factory.annotation.Autowired
    BusinessTaskScopedTokenLifecycleService tokens;
    @org.springframework.beans.factory.annotation.Autowired
    BusinessTaskScopedTokenRepository tokenRepository;
    @org.springframework.beans.factory.annotation.Autowired
    BusinessTaskTerminalStateRepository terminalStates;
    @org.springframework.beans.factory.annotation.Autowired
    TaskTerminalCleanupPlanRepository cleanupPlans;
    @org.springframework.beans.factory.annotation.Autowired
    WorkerLifecycleReconciliationCommitService reconciliation;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleEnrollmentService enrollment;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleWriterProofRepository proofs;
    @org.springframework.beans.factory.annotation.Autowired
    ClientAppRepository clientApps;
    @org.springframework.beans.factory.annotation.Autowired
    RuntimeTaskClosureService closure;
    @org.springframework.beans.factory.annotation.Autowired
    RuntimeStateAuditService stateAudit;
    @org.springframework.beans.factory.annotation.Autowired
    ProviderFixture provider;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleEffectOutboxRepository outbox;
    @org.springframework.beans.factory.annotation.Autowired
    RuntimeRequestAuditRepository requestAudits;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleWriterProofReferenceRepository proofReferences;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleEnrollmentRetirementPort retirement;
    @org.springframework.beans.factory.annotation.Autowired
    WriterExclusivityProofService writerProofs;

    private final WorkerLifecycleIdentity identity =
            new WorkerLifecycleIdentity(
                    "worker-business", "generation-business",
                    "epoch-business");
    private final WorkerLifecycleTask workerTask =
            new WorkerLifecycleTask(
                    "task-business", "provider-task-business",
                    LifecycleOwnershipMode.SHADOW,
                    "dispatch-business", "JCS_SHA256_V1",
                    "binding-business", "EFFECT_STARTED", 1);

    @BeforeEach
    void fixture() {
        reconciliation.commit(
                new WorkerLifecycleSnapshot(
                        identity, 1, 1, true,
                        List.of(), List.of()), null);
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId("task-business");
        task.setSessionId("session-business");
        task.setProviderType("codex-biz-worker");
        task.setProviderTaskId("provider-task-business");
        task.setWorkerId("worker-business");
        task.setUserId("provider-owner-business");
        task.setTenantId("tenant-business");
        task.setAgentId("codex-biz-worker");
        task.setStatus("RUNNING");
        tasks.saveAndFlush(task);

        var proof = new com.foggy.navigator.session.lifecycle.persistence
                .LifecycleWriterProofEntity();
        proof.setProofId("proof-business");
        proof.setGenerationId("generation-writer-business");
        proof.setControllerInventoryDigest("inventory-business");
        proof.setHolderInstanceId("fixture-business");
        proof.setProofVersion(1);
        proof.setStatus("ACTIVE");
        proof.setAcquiredAt(LocalDateTime.now());
        proof.setLastVerifiedAt(LocalDateTime.now());
        proof.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        proofs.saveAndFlush(proof);
        enrollment.enroll(new LifecycleEnrollmentService.EnrollmentCommand(
                enrollmentRequest(), identity, "session-business",
                workerTask, "proof-business",
                "generation-writer-business"));
        SessionLifecycleSnapshotEntity lane = sessions.findById(
                "session-business").orElseThrow();
        lane.setForegroundTaskId("task-business");
        lane.setForegroundLaneState("OCCUPIED");
        sessions.saveAndFlush(lane);

        ClientAppEntity app = new ClientAppEntity();
        app.setClientAppId("client-business");
        app.setTenantId("tenant-business");
        app.setName("fixture business");
        app.setUpstreamSystemId("upstream-business");
        app.setStatus("ENABLED");
        clientApps.saveAndFlush(app);

        BusinessTaskScopedTokenEntity token =
                new BusinessTaskScopedTokenEntity();
        token.setTokenId("token-business");
        token.setTaskId("task-business");
        token.setSessionId("session-business");
        token.setTenantId("tenant-business");
        token.setClientAppId("client-business");
        token.setUpstreamUserId("upstream-business");
        token.setNavigatorEffectiveUserId("navigator-business");
        token.setSkillId("skill-business");
        token.setWorkerPoolId("pool-business");
        token.setModelConfigId("model-business");
        token.setStatus(BusinessAgentTaskService.STATUS_ACTIVE);
        tokens.issueNewToken(token, "fixture-token-value");
        tokens.bindIssuedTokenToWorkerTask(
                "tenant-business", "token-business",
                "fixture-token-value", "provider-task-business",
                "provider-session-business", "worker-business");

        when(stateAudit.requireOwnedTask(
                "app-key", "app-secret", "upstream-business",
                "task-business")).thenReturn(
                new RuntimeStateAuditService.OwnedRuntimeTask(
                        "task-business", "session-business",
                        "provider-task-business",
                        "provider-owner-business",
                        "tenant-business", "codex-biz-worker",
                        "worker-business", "RUNNING", false, 1));
        when(stateAudit.auditTask(
                "app-key", "app-secret", "upstream-business",
                "task-business")).thenReturn(runtimeAudit());
    }

    @Test
    void terminalOwnerCommitsBusinessTombstoneAndRevokesRealToken() {
        owner.ingestNormalizedBatch("task-business", List.of(
                new NormalizedLifecycleFact(
                        "fact-business-terminal",
                        "TASK_PROVIDER_TERMINAL_OBSERVED",
                        1, "TASK", "task-business",
                        "session-business", "task-business",
                        "provider-task-business", null, identity,
                        LifecycleOwnershipMode.ENFORCED,
                        "dispatch-business",
                        "JCS_SHA256_V1", "binding-business", 2,
                        "fact-business-terminal",
                        java.time.Instant.now(),
                        java.time.Instant.now(),
                        "TASK_PROVIDER_TERMINAL_OBSERVED",
                        "CANCELLED")));

        assertThat(terminalStates
                .findByTenantIdAndWorkerTaskId(
                        "tenant-business",
                        "provider-task-business"))
                .isPresent();
        assertThat(tokenRepository.findByTokenId(
                        "token-business").orElseThrow().getStatus())
                .isEqualTo(BusinessAgentTaskService.STATUS_REVOKED);
        assertThat(cleanupPlans
                .findByIdTaskIdOrderByIdParticipant(
                        "task-business").stream()
                .filter(plan -> "PHYSICAL_TOKEN_REVOKE".equals(
                        plan.getId().getParticipant()))
                .findFirst().orElseThrow().getCheckpointState())
                .isEqualTo("COMPLETED");
        assertThat(snapshots.findById(
                        "task-business").orElseThrow().getCleanupState())
                .isEqualTo("COMPLETED");
        assertThat(proofReferences.findById(
                        "proof-business:TASK:task-business")
                .orElseThrow().getReleasedAt()).isNotNull();
        retirement.sessionClosed("session-business");
        retirement.workerRetired("worker-business");
        assertThat(proofReferences
                .countByProofIdAndReleasedAtIsNull(
                        "proof-business")).isZero();
        assertThat(writerProofs.mayReleaseProof(
                "proof-business")).isTrue();
    }

    @Test
    void publicTerminationUsesRealReceiptOwnerOutboxAndDispatcherOnce() {
        var first = closure.terminate(
                "app-key", "app-secret", "upstream-business",
                "11111111-2222-4333-8444-555555555555", "task-business",
                "worker-business", "operator-stuck-task-termination",
                "task-business", false);
        var replay = closure.terminate(
                "app-key", "app-secret", "upstream-business",
                "11111111-2222-4333-8444-555555555555", "task-business",
                "worker-business", "operator-stuck-task-termination",
                "task-business", false);

        assertThat(first.getTerminationDispatched()).isTrue();
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(replay.getIdempotentReplay()).isTrue();
        assertThat(outbox.findByIdempotencyKey(
                        "termination-intent:11111111-2222-4333-8444-555555555555")
                .orElseThrow().getEffectState())
                .isEqualTo("RESULT_OBSERVED");
    }

    @Test
    void admissionFailureKeepsRejectedAttemptReceiptAndNeverInvokesProvider() {
        var proof = proofs.findById("proof-business").orElseThrow();
        proof.setStatus("QUARANTINED");
        proofs.saveAndFlush(proof);

        var result = closure.terminate(
                "app-key", "app-secret", "upstream-business",
                "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                "task-business", "worker-business",
                "operator-stuck-task-termination",
                "task-business", false);
        var reconciliation = closure.reconcileTerminationRequest(
                "app-key", "app-secret", "upstream-business",
                "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                "task-business");

        assertThat(result.getReasonCode()).isEqualTo(
                "LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        assertThat(result.getTerminationRequestReceiptPersisted()).isTrue();
        assertThat(result.getRequestReconciliationAvailable()).isTrue();
        assertThat(result.getTerminationDispatched()).isFalse();
        assertThat(provider.invocationCount()).isZero();
        var receipt = requestAudits.findByClientRequestId(
                "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee").orElseThrow();
        assertThat(receipt.getTerminal()).isTrue();
        assertThat(receipt.getResult()).isEqualTo("FAILED");
        assertThat(receipt.getSanitizedErrorCode()).isEqualTo(
                "LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        assertThat(reconciliation.getReconciliationState()).isEqualTo(
                RuntimeTaskReconciliationState.REJECTED);
        assertThat(reconciliation.getReasonCode()).isEqualTo(
                "LIFECYCLE_WRITER_EXCLUSIVITY_LOST");
        assertThat(reconciliation.getReadOnly()).isTrue();
        assertThat(reconciliation.getNewClientRequestIdAllowed()).isFalse();
        assertThat(outbox.findByIdempotencyKey(
                "termination-intent:aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
                .isEmpty();
    }

    private LifecycleEnrollmentGate.EnrollmentRequest enrollmentRequest() {
        LocalDateTime now = LocalDateTime.now();
        return new LifecycleEnrollmentGate.EnrollmentRequest(
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
    }

    private com.foggy.navigator.claude.worker.model.dto.RuntimeTaskAuditDTO
    runtimeAudit() {
        var facts = com.foggy.navigator.claude.worker.model.dto
                .RuntimeTaskFactsDTO.builder()
                .taskId("task-business")
                .terminal(false)
                .status("RUNNING")
                .physicalWorkerId("worker-business")
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
        return com.foggy.navigator.claude.worker.model.dto
                .RuntimeTaskAuditDTO.builder()
                .taskFacts(facts)
                .status("RUNNING")
                .terminal(false)
                .build();
    }

    static final class ProviderFixture
            implements com.foggy.navigator.spi.task.RuntimeTaskClosureProvider {
        private int invocations;

        @Override
        public boolean supports(String providerType) {
            return "codex-biz-worker".equals(providerType);
        }

        @Override
        public TerminationReadiness inspect(
                String taskId, String expectedPhysicalWorkerId) {
            return new TerminationReadiness(
                    true, true, true, true, true, true, null);
        }

        @Override
        public TerminationResult terminate(
                String taskId, String ownerUserId, String tenantId,
                String expectedPhysicalWorkerId, String reason,
                String clientRequestId, boolean dryRun) {
            if (org.springframework.transaction.support
                    .TransactionSynchronizationManager
                    .isActualTransactionActive()) {
                throw new IllegalStateException(
                        "FIXTURE_PROVIDER_CALLED_INSIDE_TRANSACTION");
            }
            invocations++;
            return new TerminationResult(
                    false, true, false, true,
                    "CANCEL_REQUESTED", "rt_requestbusiness1", null);
        }

        @Override
        public TerminationAdmission prepareTerminationAdmission(
                String taskId, String ownerUserId, String tenantId,
                String expectedPhysicalWorkerId, String reason,
                String clientRequestId) {
            if (!org.springframework.transaction.support
                    .TransactionSynchronizationManager
                    .isActualTransactionActive()) {
                throw new IllegalStateException(
                        "FIXTURE_ADMISSION_NOT_TRANSACTIONAL");
            }
            return new TerminationAdmission(
                    "rt_" + clientRequestId.replace("-", ""),
                    "termination-dispatch-business", "ENFORCED",
                    "generation-business", "epoch-business",
                    "JCS_SHA256_V1", "termination-binding-business");
        }

        @Override
        public ReconciliationResult reconcile(
                String taskId, String ownerUserId, String tenantId,
                String expectedPhysicalWorkerId, int expectedDispatchCount,
                String clientRequestId, boolean dryRun) {
            return new ReconciliationResult(
                    false, true, "CANCEL_REQUESTED",
                    "DURABLE_REQUEST", null);
        }

        int invocationCount() {
            return invocations;
        }
    }
}
