package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.TestApplication;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.RuntimeRequestAuditEntity;
import com.foggy.navigator.business.agent.model.entity.RuntimeRequestAuditStageEntity;
import com.foggy.navigator.business.agent.service.ClientAppRuntimeCredentialResolver;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditProperties;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the transaction boundary around the one mutable
 * terminal-cleanup repair receipt. The row itself—not an in-memory retry—is
 * the idempotency authority.
 */
@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = TestApplication.class)
@Import({RuntimeRequestAuditService.class, RuntimeRequestAuditProperties.class})
class RuntimeRequestAuditTerminalCleanupRepairJpaTest {

    private static final String APP_KEY = "repair-jpa-key";
    private static final String APP_SECRET = "repair-jpa-secret";
    private static final String UPSTREAM_USER = "sim-user-jpa";
    private static final String TASK_ID = "task-cleanup-repair-jpa";
    private static final String READY_REASON = "NAVIGATOR_TERMINAL_REPUBLISH_READY";

    @MockitoBean
    private ClientAppRuntimeCredentialResolver credentialResolver;

    @MockitoSpyBean
    private RuntimeRequestAuditRepository auditRepository;

    @Autowired
    private RuntimeRequestAuditStageRepository stageRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ClientAppRepository clientAppRepository;

    @Autowired
    private RuntimeRequestAuditService service;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSameIdRegistrationAndConfirmationPersistOneReceiptAndOneTerminalOutcome()
            throws Exception {
        String requestId = UUID.randomUUID().toString();
        createClientApp();
        when(credentialResolver.resolve(APP_KEY, APP_SECRET))
                .thenReturn(Optional.of(resolvedCredential()));

        CountDownLatch bothInitialReads = new CountDownLatch(2);
        CountDownLatch allowInitialReadsToReturnEmpty = new CountDownLatch(1);
        AtomicInteger forcedInitialReads = new AtomicInteger();
        doAnswer(invocation -> {
            if (forcedInitialReads.getAndIncrement() < 2) {
                bothInitialReads.countDown();
                if (!allowInitialReadsToReturnEmpty.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting for concurrent registration");
                }
                // Both registration transactions intentionally take the
                // create branch. The database unique index decides the winner.
                return Optional.empty();
            }
            return findPersistedAudit(requestId);
        }).when(auditRepository).findByClientRequestId(eq(requestId));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RuntimeRequestAuditService.TerminalCleanupRepairRegistration> first =
                    executor.submit(() -> begin(requestId));
            Future<RuntimeRequestAuditService.TerminalCleanupRepairRegistration> second =
                    executor.submit(() -> begin(requestId));

            assertThat(bothInitialReads.await(5, TimeUnit.SECONDS)).isTrue();
            allowInitialReadsToReturnEmpty.countDown();

            List<RuntimeRequestAuditService.TerminalCleanupRepairRegistration> registrations = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(registrations).extracting(
                    RuntimeRequestAuditService.TerminalCleanupRepairRegistration::existing)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(auditRepository.count()).isEqualTo(1);
            assertStageCount(requestId,
                    RuntimeRequestAuditService.STAGE_CLIENT_REQUEST_RECEIVED, 1);
            assertStageCount(requestId,
                    RuntimeRequestAuditService.STAGE_TERMINAL_CLEANUP_REPAIR_REQUESTED, 1);

            RuntimeRequestAuditService.AuditHandle handle = registrations.stream()
                    .map(RuntimeRequestAuditService.TerminalCleanupRepairRegistration::handle)
                    .findFirst()
                    .orElseThrow();
            RuntimeRequestAuditService.TaskEvidence evidence = evidence();
            RuntimeRequestAuditService.TerminalCleanupRepairReceipt dryRun =
                    service.terminalCleanupRepairDryRunCompleted(
                            handle, evidence, true, READY_REASON);
            assertThat(dryRun.dryRunReady()).isTrue();

            Future<RuntimeRequestAuditService.TerminalCleanupRepairCompletion> firstCompletion =
                    executor.submit(() -> complete(handle, evidence));
            Future<RuntimeRequestAuditService.TerminalCleanupRepairCompletion> secondCompletion =
                    executor.submit(() -> complete(handle, evidence));
            List<RuntimeRequestAuditService.TerminalCleanupRepairCompletion> completions = List.of(
                    firstCompletion.get(10, TimeUnit.SECONDS),
                    secondCompletion.get(10, TimeUnit.SECONDS));

            assertThat(completions).extracting(
                    RuntimeRequestAuditService.TerminalCleanupRepairCompletion::idempotentReplay)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(completions).allSatisfy(completion -> {
                assertThat(completion.receipt().completed()).isTrue();
                assertThat(completion.receipt().status()).isEqualTo("REPAIRED");
            });
            // Concurrent callers may both reach the provider-neutral core
            // boundary, but only the locked durable receipt is allowed to
            // record the effective cleanup outcome.
            RuntimeRequestAuditEntity durableReceipt = findPersistedAudit(requestId)
                    .orElseThrow();
            assertThat(durableReceipt.getOperation()).isEqualTo(
                    RuntimeRequestAuditService.OPERATION_TASK_TERMINAL_CLEANUP_REPAIR);
            assertThat(durableReceipt.getTaskId()).isEqualTo(TASK_ID);
            assertThat(durableReceipt.getTerminal()).isTrue();
            assertThat(durableReceipt.getStatus()).isEqualTo("REPAIRED");
            assertThat(durableReceipt.getCompletedAt()).isNotNull();
            assertStageCount(requestId,
                    RuntimeRequestAuditService.STAGE_TERMINAL_CLEANUP_REPAIR_APPLIED, 1);
            assertStageCount(requestId,
                    RuntimeRequestAuditService.STAGE_TASK_TOKEN_REVOKED, 1);
            assertStageCount(requestId,
                    RuntimeRequestAuditService.STAGE_REQUEST_COMPLETED, 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void nonTargetConstraintFailuresAreNotConvertedToIdempotentReplays() {
        String requestId = UUID.randomUUID().toString();
        createClientApp();
        when(credentialResolver.resolve(APP_KEY, APP_SECRET))
                .thenReturn(Optional.of(resolvedCredential()));
        DataIntegrityViolationException genericFailure =
                new DataIntegrityViolationException("unrelated required-field failure");
        doAnswer(invocation -> {
            throw genericFailure;
        }).when(auditRepository).saveAndFlush(org.mockito.ArgumentMatchers.any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> begin(requestId))
                .isSameAs(genericFailure);
    }

    private RuntimeRequestAuditService.TerminalCleanupRepairRegistration begin(String requestId) {
        return service.beginTerminalCleanupRepair(
                requestId, APP_KEY, APP_SECRET, UPSTREAM_USER, TASK_ID);
    }

    private Optional<RuntimeRequestAuditEntity> findPersistedAudit(String requestId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return entityManager.createQuery("""
                            select a from RuntimeRequestAuditEntity a
                            where a.clientRequestId = :clientRequestId
                            """, RuntimeRequestAuditEntity.class)
                    .setParameter("clientRequestId", requestId)
                    .getResultStream()
                    .findFirst();
        } finally {
            entityManager.close();
        }
    }

    private RuntimeRequestAuditService.TerminalCleanupRepairCompletion complete(
            RuntimeRequestAuditService.AuditHandle handle,
            RuntimeRequestAuditService.TaskEvidence evidence) {
        return service.terminalCleanupRepairCompleted(handle, evidence, true, READY_REASON);
    }

    private void createClientApp() {
        ClientAppEntity app = new ClientAppEntity();
        app.setClientAppId("client-repair-" + UUID.randomUUID());
        app.setTenantId("tenant-repair-jpa");
        app.setName("Terminal cleanup repair JPA test");
        app.setUpstreamSystemId("foggy-world-sim");
        app.setStatus(ClientAppService.STATUS_ACTIVE);
        clientAppRepository.saveAndFlush(app);
        testClientAppId = app.getClientAppId();
    }

    private String testClientAppId;

    private ResolvedClientAppCredentialDTO resolvedCredential() {
        return ResolvedClientAppCredentialDTO.builder()
                .credentialId("credential-repair-jpa")
                .tenantId("tenant-repair-jpa")
                .clientAppId(testClientAppId)
                .build();
    }

    private RuntimeRequestAuditService.TaskEvidence evidence() {
        return new RuntimeRequestAuditService.TaskEvidence(
                TASK_ID, "FAILED", true, null, null, UPSTREAM_USER,
                "worker-repair-jpa", "model-repair-jpa", "codex-luna:high",
                0, 0, "NO_RUNTIME_MODEL_TOOL_SURFACE", "REPAIR_TEST",
                0, 0, "REPAIR_TEST", true, "REVOKED",
                false, false, false, 0, 0, 0, READY_REASON);
    }

    private void assertStageCount(String requestId, String stage, int expected) {
        List<RuntimeRequestAuditStageEntity> stages = stageRepository
                .findByClientRequestIdOrderByOccurredAtAscIdAsc(requestId);
        assertThat(stages).filteredOn(value -> stage.equals(value.getStage()))
                .hasSize(expected);
    }
}
