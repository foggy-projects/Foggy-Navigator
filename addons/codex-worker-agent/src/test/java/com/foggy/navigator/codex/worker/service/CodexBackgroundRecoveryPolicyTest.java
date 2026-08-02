package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryBounds;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryCapability;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryCapabilityDeclaration;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryPolicy;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryPolicyResolver;
import com.foggy.navigator.spi.recovery.ResolvedBackgroundRecoveryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodexBackgroundRecoveryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-03T08:00:00Z");

    private BackgroundRecoveryPolicyResolver resolver;
    private CodexBackgroundRecoveryPolicy policy;

    @BeforeEach
    void setUp() {
        resolver = mock(BackgroundRecoveryPolicyResolver.class);
        when(resolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0), enabledPolicy(2, 1)));
        policy = new CodexBackgroundRecoveryPolicy(
                resolver, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void sdkAndAppServerResolveAsIndependentProvidersWhileBizDeclaresNothing() {
        when(resolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> {
                    BackgroundRecoveryCapabilityDeclaration declaration = invocation.getArgument(0);
                    boolean enabled = CodexTaskService.CODEX_PROVIDER_TYPE.equals(
                            declaration.providerId().value());
                    return new ResolvedBackgroundRecoveryPolicy(
                            declaration, new BackgroundRecoveryPolicy(
                            enabled, enabledPolicy(2, 1).bounds()));
                });

        assertTrue(policy.providerPermits(
                CodexTaskService.CODEX_PROVIDER_TYPE, BackgroundRecoveryCapability.STARTUP_SCAN));
        assertFalse(policy.providerPermits(
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                BackgroundRecoveryCapability.STARTUP_SCAN));
        assertFalse(policy.providerPermits(
                CodexTaskService.CODEX_BIZ_PROVIDER_TYPE,
                BackgroundRecoveryCapability.STARTUP_SCAN));
    }

    @Test
    void exactProviderRuntimeMismatchFailsClosedWithoutAttemptConsumption() {
        CodexTaskEntity sdkRoutedToApp = task(
                "task-sdk-mismatch", CodexTaskService.CODEX_PROVIDER_TYPE, "APP_SERVER", 30);
        CodexTaskEntity appRoutedToSdk = task(
                "task-app-mismatch", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "SDK_EXEC", 30);

        assertEquals(CodexBackgroundRecoveryPolicy.Denial.PROVIDER_RUNTIME_MISMATCH,
                policy.tryAcquire(
                        sdkRoutedToApp, BackgroundRecoveryCapability.STARTUP_SCAN).denial());
        assertEquals(CodexBackgroundRecoveryPolicy.Denial.PROVIDER_RUNTIME_MISMATCH,
                policy.tryAcquire(
                        appRoutedToSdk, BackgroundRecoveryCapability.STARTUP_SCAN).denial());
        assertEquals(0, policy.attempts("task-sdk-mismatch"));
        assertEquals(0, policy.attempts("task-app-mismatch"));
    }

    @Test
    void attemptsAreMonotonicAcrossRoundsAndBackoffSaturatesWithinWindow() {
        CodexTaskEntity task = task("task-sdk", CodexTaskService.CODEX_PROVIDER_TYPE, "SDK_EXEC", 30);

        var first = policy.tryAcquire(task, BackgroundRecoveryCapability.DELAYED_RETRY);
        assertTrue(first.permitted());
        assertEquals(1, first.lease().attempt());
        first.lease().close();
        assertEquals(Duration.ofMillis(20),
                policy.assess(task, BackgroundRecoveryCapability.DELAYED_RETRY).backoff());

        var second = policy.tryAcquire(task, BackgroundRecoveryCapability.DELAYED_RETRY);
        assertTrue(second.permitted());
        assertEquals(2, second.lease().attempt());
        second.lease().close();

        assertEquals(CodexBackgroundRecoveryPolicy.Denial.ATTEMPTS_EXHAUSTED,
                policy.assess(task, BackgroundRecoveryCapability.DELAYED_RETRY).denial());
    }

    @Test
    void utcWindowAndLegacyNullAgeFailClosedWithoutAttemptConsumption() {
        CodexTaskEntity expired = task(
                "task-expired", CodexTaskService.CODEX_PROVIDER_TYPE, "SDK_EXEC", 7_200);
        CodexTaskEntity legacy = task(
                "task-legacy", CodexTaskService.CODEX_PROVIDER_TYPE, "SDK_EXEC", 30);
        legacy.setCreatedAtEpochMs(null);

        assertEquals(CodexBackgroundRecoveryPolicy.Denial.RECOVERY_WINDOW_EXHAUSTED,
                policy.tryAcquire(expired, BackgroundRecoveryCapability.STARTUP_SCAN).denial());
        assertEquals(CodexBackgroundRecoveryPolicy.Denial.LEGACY_TASK_AGE_UNKNOWN,
                policy.tryAcquire(legacy, BackgroundRecoveryCapability.STARTUP_SCAN).denial());
        assertEquals(0, policy.attempts("task-expired"));
        assertEquals(0, policy.attempts("task-legacy"));
    }

    @Test
    void concurrencyIsBoundedAndBucketedByExactProviderIdentity() {
        CodexTaskEntity sdkOne = task(
                "task-sdk-1", CodexTaskService.CODEX_PROVIDER_TYPE, "SDK_EXEC", 30);
        CodexTaskEntity sdkTwo = task(
                "task-sdk-2", CodexTaskService.CODEX_PROVIDER_TYPE, "SDK_EXEC", 30);
        CodexTaskEntity app = task(
                "task-app", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "APP_SERVER", 30);

        var sdkLease = policy.tryAcquire(sdkOne, BackgroundRecoveryCapability.STARTUP_SCAN);
        assertTrue(sdkLease.permitted());
        assertEquals(CodexBackgroundRecoveryPolicy.Denial.CONCURRENCY_EXHAUSTED,
                policy.tryAcquire(sdkTwo, BackgroundRecoveryCapability.STARTUP_SCAN).denial());
        var appLease = policy.tryAcquire(app, BackgroundRecoveryCapability.STARTUP_SCAN);
        assertTrue(appLease.permitted());

        sdkLease.lease().close();
        appLease.lease().close();
    }

    @Test
    void timerFireRechecksAProviderThatWasDisabledAfterScheduling() {
        CodexTaskEntity task = task(
                "task-sdk", CodexTaskService.CODEX_PROVIDER_TYPE, "SDK_EXEC", 30);
        assertTrue(policy.providerPermits(
                CodexTaskService.CODEX_PROVIDER_TYPE,
                BackgroundRecoveryCapability.DELAYED_RETRY));
        when(resolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0),
                        new BackgroundRecoveryPolicy(false, enabledPolicy(2, 1).bounds())));

        assertEquals(CodexBackgroundRecoveryPolicy.Denial.POLICY_DISABLED,
                policy.tryAcquire(task, BackgroundRecoveryCapability.DELAYED_RETRY).denial());
        assertEquals(0, policy.attempts("task-sdk"));
    }

    @Test
    void resolverFailureFailsClosedWithoutConsumingAttempt() {
        CodexTaskEntity task = task(
                "task-sdk", CodexTaskService.CODEX_PROVIDER_TYPE, "SDK_EXEC", 30);
        when(resolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenThrow(new IllegalStateException("configuration unavailable"));

        assertEquals(CodexBackgroundRecoveryPolicy.Denial.POLICY_UNAVAILABLE,
                policy.tryAcquire(task, BackgroundRecoveryCapability.DELAYED_RETRY).denial());
        assertEquals(0, policy.attempts("task-sdk"));
    }

    @Test
    void backoffAtWindowBoundaryStopsInsteadOfSchedulingADeadTimer() {
        CodexTaskEntity task = task(
                "task-sdk", CodexTaskService.CODEX_PROVIDER_TYPE, "SDK_EXEC", 30);
        BackgroundRecoveryPolicy boundaryPolicy = new BackgroundRecoveryPolicy(
                true,
                new BackgroundRecoveryBounds(
                        3,
                        Duration.ofSeconds(40),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(10),
                        1,
                        Duration.ofMinutes(1)));
        when(resolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0), boundaryPolicy));

        assertEquals(CodexBackgroundRecoveryPolicy.Denial.RECOVERY_WINDOW_EXHAUSTED,
                policy.assess(task, BackgroundRecoveryCapability.DELAYED_RETRY).denial());
        assertEquals(0, policy.attempts("task-sdk"));
    }

    private CodexTaskEntity task(
            String taskId, String providerType, String runtimeType, long ageSeconds) {
        CodexTaskEntity task = new CodexTaskEntity();
        task.setTaskId(taskId);
        task.setProviderType(providerType);
        task.setRuntimeType(runtimeType);
        task.setStatus("RUNNING");
        task.setWorkerTaskId("worker-" + taskId);
        task.setCreatedAtEpochMs(NOW.minusSeconds(ageSeconds).toEpochMilli());
        return task;
    }

    private BackgroundRecoveryPolicy enabledPolicy(int maxAttempts, int concurrency) {
        return new BackgroundRecoveryPolicy(true, new BackgroundRecoveryBounds(
                maxAttempts,
                Duration.ofHours(1),
                Duration.ofMillis(10),
                Duration.ofMillis(20),
                concurrency,
                Duration.ofMinutes(1)));
    }
}
