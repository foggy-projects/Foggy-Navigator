package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryBounds;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryCapability;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryCapabilityDeclaration;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryPolicyResolver;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryProviderId;
import com.foggy.navigator.spi.recovery.ResolvedBackgroundRecoveryPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local bounds for automatic Claude execution recovery only. */
@Component
class ClaudeBackgroundRecoveryPolicy {

    static final String PROVIDER_TYPE = "claude-worker";

    private static final Set<String> ACTIVE_STATUSES =
            Set.of("RUNNING", "AWAITING_PERMISSION", "CANCEL_REQUESTED");
    private static final BackgroundRecoveryCapabilityDeclaration DECLARATION =
            new BackgroundRecoveryCapabilityDeclaration(
                    BackgroundRecoveryProviderId.of(PROVIDER_TYPE),
                    Set.of(BackgroundRecoveryCapability.STARTUP_SCAN,
                            BackgroundRecoveryCapability.DELAYED_RETRY,
                            BackgroundRecoveryCapability.PERIODIC_RECOVERY_SCAN));

    private final BackgroundRecoveryPolicyResolver resolver;
    private final Clock clock;
    private final Map<String, RecoveryState> states = new HashMap<>();
    private int inFlight;
    private Instant lastPeriodicScanAt;

    @Autowired
    ClaudeBackgroundRecoveryPolicy(BackgroundRecoveryPolicyResolver resolver) {
        this(resolver, Clock.systemUTC());
    }

    ClaudeBackgroundRecoveryPolicy(BackgroundRecoveryPolicyResolver resolver, Clock clock) {
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver must not be null");
        this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
    }

    boolean providerPermits(BackgroundRecoveryCapability capability) {
        ResolvedBackgroundRecoveryPolicy resolved = resolve();
        return resolved != null
                && DECLARATION.equals(resolved.declaration())
                && resolved.permits(capability);
    }

    synchronized boolean claimPeriodicScanIfDue() {
        ResolvedBackgroundRecoveryPolicy resolved = resolve();
        if (resolved == null
                || !DECLARATION.equals(resolved.declaration())
                || !resolved.permits(BackgroundRecoveryCapability.PERIODIC_RECOVERY_SCAN)) {
            return false;
        }
        Instant now = clock.instant();
        if (lastPeriodicScanAt != null
                && Duration.between(lastPeriodicScanAt, now)
                .compareTo(resolved.policy().bounds().scanInterval()) < 0) {
            return false;
        }
        lastPeriodicScanAt = now;
        return true;
    }

    synchronized Assessment assess(
            ClaudeTaskEntity task, BackgroundRecoveryCapability capability) {
        return assessSnapshot(task, capability).assessment();
    }

    synchronized AttemptDecision tryAcquire(
            ClaudeTaskEntity task, BackgroundRecoveryCapability capability) {
        AssessmentSnapshot snapshot = assessSnapshot(task, capability);
        if (!snapshot.assessment().permitted()) {
            return new AttemptDecision(snapshot.assessment().denial(), null);
        }
        ResolvedBackgroundRecoveryPolicy resolved = snapshot.policy();
        if (resolved == null) {
            return new AttemptDecision(Denial.POLICY_UNAVAILABLE, null);
        }
        if (inFlight >= resolved.policy().bounds().maxConcurrentRecoveries()) {
            return new AttemptDecision(Denial.CONCURRENCY_EXHAUSTED, null);
        }

        RecoveryState state = states.get(task.getTaskId());
        int nextAttempt = state == null ? 1 : state.attempts() + 1;
        states.put(task.getTaskId(), new RecoveryState(nextAttempt));
        inFlight++;
        return new AttemptDecision(Denial.NONE, new AttemptLease(this, nextAttempt));
    }

    synchronized int attempts(String taskId) {
        RecoveryState state = states.get(taskId);
        return state == null ? 0 : state.attempts();
    }

    synchronized void clear(String taskId) {
        states.remove(taskId);
    }

    synchronized void clearAll() {
        states.clear();
        inFlight = 0;
        lastPeriodicScanAt = null;
    }

    private AssessmentSnapshot assessSnapshot(
            ClaudeTaskEntity task, BackgroundRecoveryCapability capability) {
        Evaluation evaluation = evaluate(task, capability);
        if (evaluation.denial() != Denial.NONE) {
            return new AssessmentSnapshot(
                    new Assessment(evaluation.denial(), Duration.ZERO), null);
        }

        int attempts = states.getOrDefault(task.getTaskId(), new RecoveryState(0)).attempts();
        BackgroundRecoveryBounds bounds = evaluation.policy().policy().bounds();
        if (attempts >= bounds.maxAttempts()) {
            return new AssessmentSnapshot(
                    new Assessment(Denial.ATTEMPTS_EXHAUSTED, Duration.ZERO),
                    evaluation.policy());
        }
        Duration backoff = boundedBackoff(bounds, attempts);
        Duration remaining = bounds.recoveryWindow().minus(evaluation.taskAge());
        if (backoff.compareTo(remaining) >= 0) {
            return new AssessmentSnapshot(
                    new Assessment(Denial.RECOVERY_WINDOW_EXHAUSTED, Duration.ZERO),
                    evaluation.policy());
        }
        return new AssessmentSnapshot(
                new Assessment(Denial.NONE, backoff), evaluation.policy());
    }

    private Evaluation evaluate(
            ClaudeTaskEntity task, BackgroundRecoveryCapability capability) {
        if (task == null || task.getTaskId() == null || task.getTaskId().isBlank()) {
            return Evaluation.denied(Denial.TASK_INELIGIBLE);
        }
        if ("A2A".equals(task.getSource())) {
            return Evaluation.denied(Denial.TASK_INELIGIBLE);
        }
        if (task.getStatus() == null || !ACTIVE_STATUSES.contains(task.getStatus())) {
            return Evaluation.denied(Denial.TASK_INELIGIBLE);
        }
        if (task.getWorkerId() == null || task.getWorkerId().isBlank()
                || task.getSessionId() == null || task.getSessionId().isBlank()) {
            return Evaluation.denied(Denial.TASK_INELIGIBLE);
        }
        if (task.getCreatedAtEpochMs() == null) {
            return Evaluation.denied(Denial.LEGACY_TASK_AGE_UNKNOWN);
        }
        if (task.getCreatedAtEpochMs() <= 0) {
            return Evaluation.denied(Denial.TASK_AGE_INVALID);
        }

        ResolvedBackgroundRecoveryPolicy resolved = resolve();
        if (resolved == null || !DECLARATION.equals(resolved.declaration())) {
            return Evaluation.denied(Denial.POLICY_UNAVAILABLE);
        }
        if (!resolved.permits(capability)) {
            return Evaluation.denied(Denial.POLICY_DISABLED);
        }

        Instant createdAt = Instant.ofEpochMilli(task.getCreatedAtEpochMs());
        Instant now = clock.instant();
        if (createdAt.isAfter(now)) {
            return Evaluation.denied(Denial.TASK_AGE_INVALID);
        }
        Duration age = Duration.between(createdAt, now);
        if (age.compareTo(resolved.policy().bounds().recoveryWindow()) >= 0) {
            return Evaluation.denied(Denial.RECOVERY_WINDOW_EXHAUSTED);
        }
        return new Evaluation(Denial.NONE, resolved, age);
    }

    private ResolvedBackgroundRecoveryPolicy resolve() {
        try {
            return resolver.resolve(DECLARATION);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Duration boundedBackoff(BackgroundRecoveryBounds bounds, int attempts) {
        Duration delay = bounds.initialBackoff();
        for (int index = 0; index < attempts && delay.compareTo(bounds.maxBackoff()) < 0; index++) {
            try {
                Duration doubled = delay.multipliedBy(2);
                delay = doubled.compareTo(bounds.maxBackoff()) > 0
                        ? bounds.maxBackoff() : doubled;
            } catch (ArithmeticException overflow) {
                return bounds.maxBackoff();
            }
        }
        return delay;
    }

    private synchronized void release() {
        if (inFlight > 0) inFlight--;
    }

    enum Denial {
        NONE,
        POLICY_DISABLED,
        POLICY_UNAVAILABLE,
        TASK_INELIGIBLE,
        LEGACY_TASK_AGE_UNKNOWN,
        TASK_AGE_INVALID,
        RECOVERY_WINDOW_EXHAUSTED,
        ATTEMPTS_EXHAUSTED,
        CONCURRENCY_EXHAUSTED
    }

    record Assessment(Denial denial, Duration backoff) {
        boolean permitted() {
            return denial == Denial.NONE;
        }
    }

    record AttemptDecision(Denial denial, AttemptLease lease) {
        boolean permitted() {
            return denial == Denial.NONE && lease != null;
        }
    }

    static final class AttemptLease implements AutoCloseable {
        private final ClaudeBackgroundRecoveryPolicy owner;
        private final int attempt;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private AttemptLease(ClaudeBackgroundRecoveryPolicy owner, int attempt) {
            this.owner = owner;
            this.attempt = attempt;
        }

        int attempt() {
            return attempt;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) owner.release();
        }
    }

    private record RecoveryState(int attempts) {
    }

    private record Evaluation(
            Denial denial,
            ResolvedBackgroundRecoveryPolicy policy,
            Duration taskAge) {
        private static Evaluation denied(Denial denial) {
            return new Evaluation(denial, null, Duration.ZERO);
        }
    }

    private record AssessmentSnapshot(
            Assessment assessment,
            ResolvedBackgroundRecoveryPolicy policy) {
    }
}
