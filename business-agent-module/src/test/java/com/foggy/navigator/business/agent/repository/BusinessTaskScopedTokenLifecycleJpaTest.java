package com.foggy.navigator.business.agent.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.TestApplication;
import com.foggy.navigator.business.agent.config.BusinessTaskScopedTokenProperties;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskScopedTokenRuntimeStore;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenLifecycleService;
import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
        BusinessTaskScopedTokenPolicyService.class,
        BusinessAgentTaskScopedTokenRuntimeStore.class,
        BusinessTaskScopedTokenLifecycleJpaTest.TokenPolicyTestConfiguration.class,
        BusinessTaskScopedTokenLifecycleJpaTest.TaskCreationTransactionHarness.class
})
class BusinessTaskScopedTokenLifecycleJpaTest {

    private static final String TENANT_ID = "tenant-token-jpa";

    private final BusinessTaskScopedTokenRepository tokenRepository;
    private final BusinessTaskScopedTokenLifecycleService lifecycleService;
    private final BusinessAgentTaskScopedTokenRuntimeStore runtimeStore;
    private final TaskCreationTransactionHarness transactionHarness;

    @Autowired
    BusinessTaskScopedTokenLifecycleJpaTest(
            BusinessTaskScopedTokenRepository tokenRepository,
            BusinessTaskScopedTokenLifecycleService lifecycleService,
            BusinessAgentTaskScopedTokenRuntimeStore runtimeStore,
            TaskCreationTransactionHarness transactionHarness) {
        this.tokenRepository = tokenRepository;
        this.lifecycleService = lifecycleService;
        this.runtimeStore = runtimeStore;
        this.transactionHarness = transactionHarness;
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
        String uniqueSuffix = suffix + "-" + UUID.randomUUID().toString().replace("-", "");
        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setTokenId("tst-" + uniqueSuffix);
        token.setTokenHash(sha256(plainToken));
        token.setTaskId("task-" + uniqueSuffix);
        token.setSessionId("session-" + uniqueSuffix);
        token.setTenantId(TENANT_ID);
        token.setClientAppId("client-app-jpa");
        token.setUpstreamUserId("upstream-user-jpa");
        token.setNavigatorEffectiveUserId("navigator-user-jpa");
        token.setSkillId("skill-jpa");
        token.setWorkerPoolId("worker-pool-jpa");
        token.setModelConfigId("model-config-jpa");
        token.setStatus(BusinessAgentTaskService.STATUS_ACTIVE);
        return token;
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

        TaskCreationTransactionHarness(BusinessTaskScopedTokenLifecycleService lifecycleService) {
            this.lifecycleService = lifecycleService;
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
