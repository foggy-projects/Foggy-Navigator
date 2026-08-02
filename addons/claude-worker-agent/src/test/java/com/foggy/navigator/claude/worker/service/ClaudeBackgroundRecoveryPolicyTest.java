package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
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
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaudeBackgroundRecoveryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-03T08:00:00Z");

    private BackgroundRecoveryPolicyResolver resolver;
    private MutableClock clock;
    private ClaudeBackgroundRecoveryPolicy policy;

    @BeforeEach
    void setUp() {
        resolver = mock(BackgroundRecoveryPolicyResolver.class);
        when(resolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0), enabledPolicy(2, 1)));
        clock = new MutableClock(NOW);
        policy = new ClaudeBackgroundRecoveryPolicy(resolver, clock);
    }

    @Test
    void declarationUsesExactClaudeProviderAndAllThreeAutomaticCapabilities() {
        assertTrue(policy.providerPermits(BackgroundRecoveryCapability.STARTUP_SCAN));
        assertTrue(policy.providerPermits(BackgroundRecoveryCapability.DELAYED_RETRY));
        assertTrue(policy.providerPermits(BackgroundRecoveryCapability.PERIODIC_RECOVERY_SCAN));
    }

    @Test
    void attemptsRemainMonotonicAndStopAtFiniteBound() {
        ClaudeTaskEntity task = task("task-1", 30);

        var first = policy.tryAcquire(task, BackgroundRecoveryCapability.STARTUP_SCAN);
        assertTrue(first.permitted());
        assertEquals(1, first.lease().attempt());
        first.lease().close();
        assertEquals(Duration.ofMillis(20),
                policy.assess(task, BackgroundRecoveryCapability.DELAYED_RETRY).backoff());

        var second = policy.tryAcquire(task, BackgroundRecoveryCapability.PERIODIC_RECOVERY_SCAN);
        assertTrue(second.permitted());
        assertEquals(2, second.lease().attempt());
        second.lease().close();
        assertEquals(ClaudeBackgroundRecoveryPolicy.Denial.ATTEMPTS_EXHAUSTED,
                policy.assess(task, BackgroundRecoveryCapability.DELAYED_RETRY).denial());
    }

    @Test
    void legacyNullAndA2aFailClosedWithoutAttemptConsumption() {
        ClaudeTaskEntity legacy = task("legacy", 30);
        legacy.setCreatedAtEpochMs(null);
        ClaudeTaskEntity a2a = task("a2a", 30);
        a2a.setSource("A2A");

        assertEquals(ClaudeBackgroundRecoveryPolicy.Denial.LEGACY_TASK_AGE_UNKNOWN,
                policy.tryAcquire(legacy, BackgroundRecoveryCapability.STARTUP_SCAN).denial());
        assertEquals(ClaudeBackgroundRecoveryPolicy.Denial.TASK_INELIGIBLE,
                policy.tryAcquire(a2a, BackgroundRecoveryCapability.STARTUP_SCAN).denial());
        assertEquals(0, policy.attempts("legacy"));
        assertEquals(0, policy.attempts("a2a"));
    }

    @Test
    void concurrencyLeaseIsReleasedExactlyOnce() {
        var first = policy.tryAcquire(task("task-1", 30), BackgroundRecoveryCapability.STARTUP_SCAN);
        assertTrue(first.permitted());
        assertEquals(ClaudeBackgroundRecoveryPolicy.Denial.CONCURRENCY_EXHAUSTED,
                policy.tryAcquire(task("task-2", 30), BackgroundRecoveryCapability.STARTUP_SCAN).denial());

        first.lease().close();
        first.lease().close();
        var second = policy.tryAcquire(task("task-2", 30), BackgroundRecoveryCapability.STARTUP_SCAN);
        assertTrue(second.permitted());
        second.lease().close();
    }

    @Test
    void periodicScanRespectsConfiguredInterval() {
        assertTrue(policy.claimPeriodicScanIfDue());
        assertFalse(policy.claimPeriodicScanIfDue());
        clock.advance(Duration.ofSeconds(59));
        assertFalse(policy.claimPeriodicScanIfDue());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(policy.claimPeriodicScanIfDue());
    }

    @Test
    void dynamicDisableFailsClosedAtTimerFireWithoutAttempt() {
        ClaudeTaskEntity task = task("task-1", 30);
        when(resolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0),
                        new BackgroundRecoveryPolicy(false, enabledPolicy(2, 1).bounds())));

        assertEquals(ClaudeBackgroundRecoveryPolicy.Denial.POLICY_DISABLED,
                policy.tryAcquire(task, BackgroundRecoveryCapability.DELAYED_RETRY).denial());
        assertEquals(0, policy.attempts("task-1"));
    }

    @Test
    void expiredWindowAndDeadTimerBoundaryStop() {
        assertEquals(ClaudeBackgroundRecoveryPolicy.Denial.RECOVERY_WINDOW_EXHAUSTED,
                policy.assess(task("expired", 3_600), BackgroundRecoveryCapability.STARTUP_SCAN).denial());

        BackgroundRecoveryPolicy boundary = new BackgroundRecoveryPolicy(true,
                new BackgroundRecoveryBounds(3, Duration.ofSeconds(40), Duration.ofSeconds(10),
                        Duration.ofSeconds(10), 1, Duration.ofMinutes(1)));
        when(resolver.resolve(any(BackgroundRecoveryCapabilityDeclaration.class)))
                .thenAnswer(invocation -> new ResolvedBackgroundRecoveryPolicy(
                        invocation.getArgument(0), boundary));
        assertEquals(ClaudeBackgroundRecoveryPolicy.Denial.RECOVERY_WINDOW_EXHAUSTED,
                policy.assess(task("boundary", 30), BackgroundRecoveryCapability.DELAYED_RETRY).denial());
    }

    private ClaudeTaskEntity task(String taskId, long ageSeconds) {
        ClaudeTaskEntity task = new ClaudeTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId("session-" + taskId);
        task.setWorkerId("worker-1");
        task.setStatus("RUNNING");
        task.setSource("PLATFORM");
        task.setCreatedAtEpochMs(clock.instant().minusSeconds(ageSeconds).toEpochMilli());
        return task;
    }

    private BackgroundRecoveryPolicy enabledPolicy(int maxAttempts, int concurrency) {
        return new BackgroundRecoveryPolicy(true, new BackgroundRecoveryBounds(
                maxAttempts, Duration.ofHours(1), Duration.ofMillis(10), Duration.ofMillis(20),
                concurrency, Duration.ofMinutes(1)));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
