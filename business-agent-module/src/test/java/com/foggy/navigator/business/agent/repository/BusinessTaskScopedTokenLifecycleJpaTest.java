package com.foggy.navigator.business.agent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.TestApplication;
import com.foggy.navigator.business.agent.config.BusinessTaskScopedTokenProperties;
import com.foggy.navigator.business.agent.event.BusinessTaskScopedTokenTerminalListener;
import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskTerminalStateEntity;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskScopedTokenRuntimeStore;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenLifecycleService;
import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenPolicyService;
import com.foggy.navigator.business.agent.service.TerminalTaskBindingException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = TestApplication.class)
@Import({
        BusinessTaskScopedTokenLifecycleService.class,
        BusinessTaskScopedTokenTerminalListener.class,
        BusinessTaskScopedTokenPolicyService.class,
        BusinessAgentTaskScopedTokenRuntimeStore.class,
        BusinessTaskScopedTokenLifecycleJpaTest.TokenPolicyTestConfiguration.class,
        BusinessTaskScopedTokenLifecycleJpaTest.TaskCreationTransactionHarness.class
})
class BusinessTaskScopedTokenLifecycleJpaTest {

    private static final String TENANT_ID = "tenant-token-jpa";

    private final BusinessTaskScopedTokenRepository tokenRepository;
    private final BusinessTaskScopedTokenLifecycleService lifecycleService;
    private final BusinessTaskTerminalStateRepository terminalStateRepository;
    private final BusinessAgentTaskScopedTokenRuntimeStore runtimeStore;
    private final TaskCreationTransactionHarness transactionHarness;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    BusinessTaskScopedTokenLifecycleJpaTest(
            BusinessTaskScopedTokenRepository tokenRepository,
            BusinessTaskScopedTokenLifecycleService lifecycleService,
            BusinessTaskTerminalStateRepository terminalStateRepository,
            BusinessAgentTaskScopedTokenRuntimeStore runtimeStore,
            TaskCreationTransactionHarness transactionHarness,
            ApplicationEventPublisher eventPublisher) {
        this.tokenRepository = tokenRepository;
        this.lifecycleService = lifecycleService;
        this.terminalStateRepository = terminalStateRepository;
        this.runtimeStore = runtimeStore;
        this.transactionHarness = transactionHarness;
        this.eventPublisher = eventPublisher;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void taskCreationRollbackRevokesAlreadyCommittedCapabilityAndRuntimeAlias() {
        String plainToken = "btt_rollback_compensation";
        BusinessTaskScopedTokenEntity token = newToken("rollback", plainToken);

        assertThatThrownBy(() -> transactionHarness.issueThenRollback(token, plainToken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated task creation rollback");

        BusinessTaskScopedTokenEntity persisted = tokenRepository
                .findByTokenIdAndTenantId(token.getTokenId(), TENANT_ID)
                .orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(BusinessAgentTaskService.STATUS_REVOKED);
        assertThat(persisted.getRevokedAt()).isNotNull();
        assertThat(persisted.getRevokeReason()).isEqualTo("task creation transaction rolled back");
        assertThat(runtimeStore.getToken(TENANT_ID, token.getSessionId(), token.getTaskId())).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void workerTaskTerminalRevocationLocksPersistedBindingAndRemovesRuntimeAliases() {
        String plainToken = "btt_worker_terminal";
        BusinessTaskScopedTokenEntity token = lifecycleService.issueNewToken(
                newToken("worker-terminal", plainToken), plainToken);
        lifecycleService.bindIssuedTokenToWorkerTask(
                TENANT_ID,
                token.getTokenId(),
                plainToken,
                "worker-task-terminal",
                "worker-session-terminal",
                "worker-terminal");

        int revoked = lifecycleService.revokeTaskScopedTokensForWorkerTask(
                TENANT_ID,
                "worker-task-terminal",
                "system:task-lifecycle",
                "worker task reached terminal status: COMPLETED");

        assertThat(revoked).isEqualTo(1);
        BusinessTaskScopedTokenEntity persisted = tokenRepository
                .findByTokenIdAndTenantId(token.getTokenId(), TENANT_ID)
                .orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(BusinessAgentTaskService.STATUS_REVOKED);
        assertThat(persisted.getRevokedAt()).isNotNull();
        assertThat(runtimeStore.getToken(
                TENANT_ID, token.getSessionId(), "worker-task-terminal")).isNull();
        assertThat(runtimeStore.getToken(
                TENANT_ID, "worker-session-terminal", "worker-task-terminal")).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void preboundCapabilityKeepsExactWorkerAndLeaseThroughBindAndRevocation() {
        String plainToken = "btt_prebound_lifecycle";
        BusinessTaskScopedTokenEntity token = lifecycleService.issuePreboundToken(
                newToken("prebound-lifecycle", plainToken),
                plainToken,
                "worker-prebound",
                "bwl-prebound");

        BusinessTaskScopedTokenEntity issued = tokenRepository
                .findByTokenIdAndTenantId(token.getTokenId(), TENANT_ID)
                .orElseThrow();
        assertThat(issued.getWorkerId()).isEqualTo("worker-prebound");
        assertThat(issued.getWorkerLeaseId()).isEqualTo("bwl-prebound");
        assertThat(issued.getWorkerTaskId()).isNull();

        lifecycleService.bindIssuedTokenToWorkerTask(
                TENANT_ID,
                token.getTokenId(),
                plainToken,
                "worker-task-prebound",
                "worker-session-prebound",
                "worker-prebound",
                "bwl-prebound");
        lifecycleService.revokeTaskScopedToken(
                TENANT_ID, token.getTokenId(), "system", "prebound lifecycle test");

        assertThatThrownBy(() -> lifecycleService.bindIssuedTokenToWorkerTask(
                TENANT_ID,
                token.getTokenId(),
                plainToken,
                "worker-task-prebound",
                "worker-session-prebound",
                "worker-prebound",
                "bwl-prebound"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("token is revoked");
        BusinessTaskScopedTokenEntity revoked = tokenRepository
                .findByTokenIdAndTenantId(token.getTokenId(), TENANT_ID)
                .orElseThrow();
        assertThat(revoked.getStatus()).isEqualTo(BusinessAgentTaskService.STATUS_REVOKED);
        assertThat(revoked.getWorkerId()).isEqualTo("worker-prebound");
        assertThat(revoked.getWorkerLeaseId()).isEqualTo("bwl-prebound");
        assertThat(revoked.getWorkerTaskId()).isEqualTo("worker-task-prebound");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void terminalEventBeforeWorkerBindingCreatesUnboundMarkerAndRejectsLateBind() {
        String plainToken = "btt_terminal_before_bind";
        BusinessTaskScopedTokenEntity token = lifecycleService.issueNewToken(
                newToken("terminal-before-bind", plainToken), plainToken);

        eventPublisher.publishEvent(terminalEvent(
                token, "worker-task-before-bind", "FAILED"));

        BusinessTaskScopedTokenEntity persisted = tokenRepository
                .findByTokenIdAndTenantId(token.getTokenId(), TENANT_ID)
                .orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(BusinessAgentTaskService.STATUS_ACTIVE);
        BusinessTaskTerminalStateEntity marker = terminalStateRepository
                .findByTenantIdAndWorkerTaskId(TENANT_ID, "worker-task-before-bind")
                .orElseThrow();
        assertThat(marker.getBusinessTaskId()).isNull();
        assertThat(marker.getNavigatorEffectiveUserId()).isNull();
        assertThat(marker.getProviderTaskUserId()).isEqualTo("provider-owner-jpa");
        assertThat(marker.getRevocationCompletedAt()).isNull();
        assertThatThrownBy(() -> lifecycleService.bindIssuedTokenToWorkerTask(
                TENANT_ID,
                token.getTokenId(),
                plainToken,
                "worker-task-before-bind",
                "worker-session-before-bind",
                "worker-before-bind"))
                .isInstanceOf(TerminalTaskBindingException.class)
                .hasMessage("cannot bind task token to a terminal worker task");
        BusinessTaskScopedTokenEntity rejectedBinding = tokenRepository
                .findByTokenId(token.getTokenId()).orElseThrow();
        assertThat(rejectedBinding.getStatus())
                .isEqualTo(BusinessAgentTaskService.STATUS_REVOKED);
        assertThat(rejectedBinding.getWorkerTaskId()).isEqualTo("worker-task-before-bind");
        assertThat(rejectedBinding.getWorkerSessionId()).isEqualTo("worker-session-before-bind");
        assertThat(rejectedBinding.getWorkerId()).isEqualTo("worker-before-bind");
        assertThat(rejectedBinding.getRevokedAt()).isNotNull();

        marker = terminalStateRepository
                .findByTenantIdAndWorkerTaskId(TENANT_ID, "worker-task-before-bind")
                .orElseThrow();
        assertThat(marker.getBusinessTaskId()).isEqualTo(token.getTaskId());
        assertThat(marker.getNavigatorEffectiveUserId()).isEqualTo("navigator-user-jpa");
        assertThat(marker.getRevocationCompletedAt()).isNotNull();

        // Simulate a failed/incorrect physical compensation that reopens the
        // token row. The enriched tombstone must remain the authorization
        // authority through either correlation key.
        rejectedBinding.setStatus(BusinessAgentTaskService.STATUS_ACTIVE);
        rejectedBinding.setRevokedAt(null);
        rejectedBinding.setRevokedBy(null);
        rejectedBinding.setRevokeReason(null);
        tokenRepository.saveAndFlush(rejectedBinding);

        BusinessTaskScopedTokenEntity businessKeyOnly = tokenRepository
                .findByTokenId(token.getTokenId()).orElseThrow();
        businessKeyOnly.setWorkerTaskId(null);
        assertThatThrownBy(() -> lifecycleService.requireNotTerminal(businessKeyOnly))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("task token belongs to a terminal task");

        businessKeyOnly.setWorkerTaskId("worker-task-before-bind");
        businessKeyOnly.setTaskId("simulated-missing-business-correlation");
        assertThatThrownBy(() -> lifecycleService.requireNotTerminal(businessKeyOnly))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("task token belongs to a terminal task");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void terminalMarkerCorrelationMismatchPreservesMarkerButCommitsRevokedBinding() {
        String plainToken = "btt_terminal_mismatch";
        BusinessTaskScopedTokenEntity token = lifecycleService.issueNewToken(
                newToken("terminal-mismatch", plainToken), plainToken);
        String workerTaskId = "worker-task-terminal-mismatch";
        eventPublisher.publishEvent(terminalEvent(token, workerTaskId, "COMPLETED"));

        BusinessTaskTerminalStateEntity marker = terminalStateRepository
                .findByTenantIdAndWorkerTaskId(TENANT_ID, workerTaskId)
                .orElseThrow();
        marker.setBusinessTaskId("foreign-business-task");
        marker.setNavigatorEffectiveUserId("foreign-capability-actor");
        terminalStateRepository.saveAndFlush(marker);

        assertThatThrownBy(() -> lifecycleService.bindIssuedTokenToWorkerTask(
                TENANT_ID,
                token.getTokenId(),
                plainToken,
                workerTaskId,
                "worker-session-terminal-mismatch",
                "worker-terminal-mismatch"))
                .isInstanceOf(TerminalTaskBindingException.class)
                .hasMessage("terminal tombstone capability correlation mismatch");

        BusinessTaskScopedTokenEntity rejectedBinding = tokenRepository
                .findByTokenId(token.getTokenId()).orElseThrow();
        assertThat(rejectedBinding.getStatus())
                .isEqualTo(BusinessAgentTaskService.STATUS_REVOKED);
        assertThat(rejectedBinding.getWorkerTaskId()).isEqualTo(workerTaskId);
        assertThat(rejectedBinding.getWorkerSessionId())
                .isEqualTo("worker-session-terminal-mismatch");
        assertThat(rejectedBinding.getWorkerId()).isEqualTo("worker-terminal-mismatch");

        BusinessTaskTerminalStateEntity preservedMarker = terminalStateRepository
                .findByTenantIdAndWorkerTaskId(TENANT_ID, workerTaskId)
                .orElseThrow();
        assertThat(preservedMarker.getBusinessTaskId()).isEqualTo("foreign-business-task");
        assertThat(preservedMarker.getNavigatorEffectiveUserId())
                .isEqualTo("foreign-capability-actor");
        assertThat(preservedMarker.getRevocationCompletedAt()).isNull();

        rejectedBinding.setStatus(BusinessAgentTaskService.STATUS_ACTIVE);
        rejectedBinding.setRevokedAt(null);
        rejectedBinding.setRevokedBy(null);
        rejectedBinding.setRevokeReason(null);
        tokenRepository.saveAndFlush(rejectedBinding);
        rejectedBinding.setTaskId("does-not-match-preserved-marker");

        assertThatThrownBy(() -> lifecycleService.requireNotTerminal(rejectedBinding))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("task token belongs to a terminal task");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sameWorkerTaskIdInDifferentTenantsOnlyClosesMatchingTenantCapability() {
        String otherTenant = "tenant-token-jpa-other";
        String workerTaskId = "shared-worker-task-id";
        BusinessTaskScopedTokenEntity first = lifecycleService.issueNewToken(
                newToken(TENANT_ID, "tenant-one", "btt_tenant_one"), "btt_tenant_one");
        BusinessTaskScopedTokenEntity second = lifecycleService.issueNewToken(
                newToken(otherTenant, "tenant-two", "btt_tenant_two"), "btt_tenant_two");
        lifecycleService.bindIssuedTokenToWorkerTask(
                TENANT_ID, first.getTokenId(), "btt_tenant_one", workerTaskId, null, "worker-one");
        lifecycleService.bindIssuedTokenToWorkerTask(
                otherTenant, second.getTokenId(), "btt_tenant_two", workerTaskId, null, "worker-two");

        eventPublisher.publishEvent(terminalEvent(first, workerTaskId, "TIMED_OUT"));

        assertThat(tokenRepository.findByTokenId(first.getTokenId()).orElseThrow().getStatus())
                .isEqualTo(BusinessAgentTaskService.STATUS_REVOKED);
        assertThat(tokenRepository.findByTokenId(second.getTokenId()).orElseThrow().getStatus())
                .isEqualTo(BusinessAgentTaskService.STATUS_ACTIVE);
        assertThat(terminalStateRepository.existsByTenantIdAndWorkerTaskIdAndExpiresAtAfter(
                TENANT_ID, workerTaskId, java.time.LocalDateTime.now())).isTrue();
        assertThat(terminalStateRepository.existsByTenantIdAndWorkerTaskIdAndExpiresAtAfter(
                otherTenant, workerTaskId, java.time.LocalDateTime.now())).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void durableTombstoneAcceptsOpenApiProviderOwnerDifferentFromCapabilityActorAndCanBeRetried() {
        String plainToken = "btt_tombstone_retry";
        BusinessTaskScopedTokenEntity token = lifecycleService.issueNewToken(
                newToken("tombstone-retry", plainToken), plainToken);
        lifecycleService.bindIssuedTokenToWorkerTask(
                TENANT_ID,
                token.getTokenId(),
                plainToken,
                "worker-task-retry",
                null,
                "worker-retry");
        boolean recorded = lifecycleService.recordTerminalState(
                TENANT_ID,
                "worker-task-retry",
                "provider-owner-jpa",
                "langgraph-biz",
                "REJECTED");

        assertThat(recorded).isTrue();
        BusinessTaskTerminalStateEntity terminal = terminalStateRepository
                .findByTenantIdAndWorkerTaskId(TENANT_ID, "worker-task-retry")
                .orElseThrow();
        assertThat(terminal.getProviderTaskUserId()).isEqualTo("provider-owner-jpa");
        assertThat(terminal.getNavigatorEffectiveUserId()).isEqualTo("navigator-user-jpa");
        assertThat(terminal.getBusinessTaskId()).isEqualTo(token.getTaskId());
        assertThat(tokenRepository.findByTokenId(token.getTokenId()).orElseThrow().getStatus())
                .isEqualTo(BusinessAgentTaskService.STATUS_ACTIVE);
        assertThatThrownBy(() -> lifecycleService.requireNotTerminal(
                tokenRepository.findByTokenId(token.getTokenId()).orElseThrow()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("task token belongs to a terminal task");

        int revoked = lifecycleService.materializeTerminalRevocation(
                TENANT_ID,
                "worker-task-retry",
                "retry-test");

        assertThat(revoked).isEqualTo(1);
        assertThat(tokenRepository.findByTokenId(token.getTokenId()).orElseThrow().getStatus())
                .isEqualTo(BusinessAgentTaskService.STATUS_REVOKED);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void beforeCommitTombstoneRollsBackWithProviderTransactionAndReplaySucceeds() {
        String plainToken = "btt_before_commit_retry";
        BusinessTaskScopedTokenEntity token = lifecycleService.issueNewToken(
                newToken("before-commit-retry", plainToken), plainToken);
        lifecycleService.bindIssuedTokenToWorkerTask(
                TENANT_ID,
                token.getTokenId(),
                plainToken,
                "worker-task-before-commit",
                null,
                "worker-before-commit");
        TaskStatusChangeEvent event = terminalEvent(token, "worker-task-before-commit", "FAILED");

        assertThatThrownBy(() -> transactionHarness.publishThenFailLateBeforeCommit(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated late before-commit failure");
        assertThat(terminalStateRepository.existsByTenantIdAndWorkerTaskIdAndExpiresAtAfter(
                TENANT_ID, "worker-task-before-commit", java.time.LocalDateTime.now())).isFalse();
        assertThat(tokenRepository.findByTokenId(token.getTokenId()).orElseThrow().getStatus())
                .isEqualTo(BusinessAgentTaskService.STATUS_ACTIVE);

        transactionHarness.publishAndCommit(event);

        assertThat(terminalStateRepository.existsByTenantIdAndWorkerTaskIdAndExpiresAtAfter(
                TENANT_ID, "worker-task-before-commit", java.time.LocalDateTime.now())).isTrue();
        assertThat(tokenRepository.findByTokenId(token.getTokenId()).orElseThrow().getStatus())
                .isEqualTo(BusinessAgentTaskService.STATUS_REVOKED);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentBindAndRevokeCannotResurrectCapability() throws Exception {
        String plainToken = "btt_concurrent_lock";
        BusinessTaskScopedTokenEntity token = lifecycleService.issueNewToken(
                newToken("concurrent", plainToken), plainToken);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> bindResult = executor.submit(() -> runConcurrently(ready, start, () ->
                    lifecycleService.bindIssuedTokenToWorkerTask(
                            TENANT_ID,
                            token.getTokenId(),
                            plainToken,
                            "worker-task-concurrent",
                            "worker-session-concurrent",
                            "worker-concurrent")));
            Future<Throwable> revokeResult = executor.submit(() -> runConcurrently(ready, start, () ->
                    lifecycleService.revokeTaskScopedToken(
                            TENANT_ID,
                            token.getTokenId(),
                            "concurrency-test",
                            "concurrent revoke")));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Throwable bindFailure = getResult(bindResult);
            Throwable revokeFailure = getResult(revokeResult);

            assertThat(revokeFailure).isNull();
            if (bindFailure != null) {
                assertThat(bindFailure)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("token is revoked");
            }
        } finally {
            executor.shutdownNow();
        }

        BusinessTaskScopedTokenEntity persisted = tokenRepository
                .findByTokenIdAndTenantId(token.getTokenId(), TENANT_ID)
                .orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(BusinessAgentTaskService.STATUS_REVOKED);
        assertThat(persisted.getRevokedAt()).isNotNull();
        assertThat(runtimeStore.getToken(TENANT_ID, token.getSessionId(), token.getTaskId())).isNull();
        assertThat(runtimeStore.getToken(
                TENANT_ID, "worker-session-concurrent", "worker-task-concurrent")).isNull();
    }

    private Throwable runConcurrently(
            CountDownLatch ready, CountDownLatch start, ThrowingRunnable action) {
        ready.countDown();
        try {
            start.await();
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private Throwable getResult(Future<Throwable> result) throws Exception {
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("concurrent lifecycle invocation escaped the result wrapper", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("concurrent lifecycle invocation timed out", e);
        }
    }

    private BusinessTaskScopedTokenEntity newToken(String suffix, String plainToken) {
        return newToken(TENANT_ID, suffix, plainToken);
    }

    private BusinessTaskScopedTokenEntity newToken(
            String tenantId, String suffix, String plainToken) {
        String uniqueSuffix = suffix + "-" + UUID.randomUUID().toString().replace("-", "");
        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setTokenId("tst-" + uniqueSuffix);
        token.setTokenHash(sha256(plainToken));
        token.setTaskId("task-" + uniqueSuffix);
        token.setSessionId("session-" + uniqueSuffix);
        token.setTenantId(tenantId);
        token.setClientAppId("client-app-jpa");
        token.setUpstreamUserId("upstream-user-jpa");
        token.setNavigatorEffectiveUserId("navigator-user-jpa");
        token.setSkillId("skill-jpa");
        token.setWorkerPoolId("worker-pool-jpa");
        token.setModelConfigId("model-config-jpa");
        token.setStatus(BusinessAgentTaskService.STATUS_ACTIVE);
        return token;
    }

    private TaskStatusChangeEvent terminalEvent(
            BusinessTaskScopedTokenEntity token, String workerTaskId, String status) {
        return TaskStatusChangeEvent.builder()
                .taskId(workerTaskId)
                .tenantId(token.getTenantId())
                .userId("provider-owner-jpa")
                .agentId("langgraph-biz")
                .status(status)
                .recoverable(false)
                .build();
    }

    private String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @TestConfiguration
    static class TokenPolicyTestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        BusinessTaskScopedTokenProperties businessTaskScopedTokenProperties() {
            return new BusinessTaskScopedTokenProperties();
        }
    }

    @Service
    static class TaskCreationTransactionHarness {

        private final BusinessTaskScopedTokenLifecycleService lifecycleService;
        private final ApplicationEventPublisher eventPublisher;

        TaskCreationTransactionHarness(
                BusinessTaskScopedTokenLifecycleService lifecycleService,
                ApplicationEventPublisher eventPublisher) {
            this.lifecycleService = lifecycleService;
            this.eventPublisher = eventPublisher;
        }

        @Transactional
        public void publishThenFailLateBeforeCommit(TaskStatusChangeEvent event) {
            eventPublisher.publishEvent(event);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public int getOrder() {
                    return 100;
                }

                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("simulated late before-commit failure");
                }
            });
        }

        @Transactional
        public void publishAndCommit(TaskStatusChangeEvent event) {
            eventPublisher.publishEvent(event);
        }

        @Transactional
        public void issueThenRollback(BusinessTaskScopedTokenEntity token, String plainToken) {
            BusinessTaskScopedTokenEntity issued = lifecycleService.issueNewToken(token, plainToken);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        lifecycleService.revokeTaskScopedToken(
                                issued.getTenantId(),
                                issued.getTokenId(),
                                "system",
                                "task creation transaction rolled back");
                    }
                }
            });
            throw new IllegalStateException("simulated task creation rollback");
        }
    }
}
