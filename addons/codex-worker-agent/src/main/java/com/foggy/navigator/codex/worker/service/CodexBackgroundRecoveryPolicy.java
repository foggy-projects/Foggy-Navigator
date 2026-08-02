package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
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

/**
 * Process-local enforcement for Codex automatic recovery. Durable task identity
 * and age remain owned by {@link CodexTaskEntity}; this class never infers a
 * missing UTC epoch from legacy local timestamps.
 */
@Component
class CodexBackgroundRecoveryPolicy {

    private static final Set<String> ACTIVE_STATUSES =
            Set.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED");
    private static final Set<BackgroundRecoveryCapability> TASK_RECOVERY_CAPABILITIES =
            Set.of(BackgroundRecoveryCapability.STARTUP_SCAN,
                    BackgroundRecoveryCapability.DELAYED_RETRY);
    private static final Map<String, BackgroundRecoveryCapabilityDeclaration> DECLARATIONS = Map.of(
            CodexTaskService.CODEX_PROVIDER_TYPE,
            new BackgroundRecoveryCapabilityDeclaration(
                    BackgroundRecoveryProviderId.of(CodexTaskService.CODEX_PROVIDER_TYPE),
                    TASK_RECOVERY_CAPABILITIES),
            CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
            new BackgroundRecoveryCapabilityDeclaration(
                    BackgroundRecoveryProviderId.of(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE),
                    TASK_RECOVERY_CAPABILITIES));

    private final BackgroundRecoveryPolicyResolver resolver;
    private final Clock clock;
    private final Map<String, RecoveryState> states = new HashMap<>();
    private final Map<String, Integer> inFlightByProvider = new HashMap<>();

    @Autowired
    CodexBackgroundRecoveryPolicy(BackgroundRecoveryPolicyResolver resolver) {
        this(resolver, Clock.systemUTC());
    }

    CodexBackgroundRecoveryPolicy(BackgroundRecoveryPolicyResolver resolver, Clock clock) {
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver must not be null");
        this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
    }

    boolean providerPermits(String providerType, BackgroundRecoveryCapability capability) {
        BackgroundRecoveryCapabilityDeclaration declaration = DECLARATIONS.get(providerType);
        if (declaration == null) return false;
        try {
            ResolvedBackgroundRecoveryPolicy resolved = resolver.resolve(declaration);
            return resolved != null
                    && declaration.equals(resolved.declaration())
                    && resolved.permits(capability);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    synchronized Assessment assess(
            CodexTaskEntity task, BackgroundRecoveryCapability capability) {
        return assessSnapshot(task, capability).assessment();
    }

    private AssessmentSnapshot assessSnapshot(
            CodexTaskEntity task, BackgroundRecoveryCapability capability) {
        Evaluation evaluation = evaluate(task, capability);
        if (evaluation.denial() != Denial.NONE) {
            return new AssessmentSnapshot(
                    new Assessment(evaluation.denial(), Duration.ZERO), null);
        }

        RecoveryState state = states.get(task.getTaskId());
        if (state != null && !state.providerType().equals(task.getProviderType())) {
            return new AssessmentSnapshot(
                    new Assessment(Denial.PROVIDER_IDENTITY_CHANGED, Duration.ZERO), null);
        }
        int attempts = state != null ? state.attempts() : 0;
        BackgroundRecoveryBounds bounds = evaluation.policy().policy().bounds();
        if (attempts >= bounds.maxAttempts()) {
            return new AssessmentSnapshot(
                    new Assessment(Denial.ATTEMPTS_EXHAUSTED, Duration.ZERO),
                    evaluation.policy());
        }

        Duration backoff = boundedBackoff(bounds, attempts);
        Duration remainingWindow = bounds.recoveryWindow().minus(evaluation.taskAge());
        if (backoff.compareTo(remainingWindow) >= 0) {
            return new AssessmentSnapshot(
                    new Assessment(Denial.RECOVERY_WINDOW_EXHAUSTED, Duration.ZERO),
                    evaluation.policy());
        }
        return new AssessmentSnapshot(
                new Assessment(Denial.NONE, backoff), evaluation.policy());
    }

    synchronized AttemptDecision tryAcquire(
            CodexTaskEntity task, BackgroundRecoveryCapability capability) {
        AssessmentSnapshot snapshot = assessSnapshot(task, capability);
        Assessment assessment = snapshot.assessment();
        if (!assessment.permitted()) {
            return new AttemptDecision(assessment.denial(), null);
        }

        ResolvedBackgroundRecoveryPolicy resolved = snapshot.policy();
        if (resolved == null) {
            return new AttemptDecision(Denial.POLICY_UNAVAILABLE, null);
        }
        String providerType = task.getProviderType();
        int inFlight = inFlightByProvider.getOrDefault(providerType, 0);
        if (inFlight >= resolved.policy().bounds().maxConcurrentRecoveries()) {
            return new AttemptDecision(Denial.CONCURRENCY_EXHAUSTED, null);
        }

        RecoveryState state = states.computeIfAbsent(task.getTaskId(),
                ignored -> new RecoveryState(providerType, 0));
        RecoveryState incremented = new RecoveryState(providerType, state.attempts() + 1);
        states.put(task.getTaskId(), incremented);
        inFlightByProvider.put(providerType, inFlight + 1);
        return new AttemptDecision(
                Denial.NONE,
                new AttemptLease(this, providerType, incremented.attempts()));
    }

    synchronized int attempts(String taskId) {
        RecoveryState state = states.get(taskId);
        return state != null ? state.attempts() : 0;
    }

    synchronized void clear(String taskId) {
        states.remove(taskId);
    }

    synchronized void clearAll() {
        states.clear();
        inFlightByProvider.clear();
    }

    private Evaluation evaluate(
            CodexTaskEntity task, BackgroundRecoveryCapability capability) {
        if (task == null || task.getTaskId() == null || task.getTaskId().isBlank()) {
            return Evaluation.denied(Denial.TASK_INELIGIBLE);
        }
        BackgroundRecoveryCapabilityDeclaration declaration = DECLARATIONS.get(task.getProviderType());
        if (declaration == null) {
            return Evaluation.denied(Denial.PROVIDER_UNDECLARED);
        }
        if (!runtimeMatchesProvider(task)) {
            return Evaluation.denied(Denial.PROVIDER_RUNTIME_MISMATCH);
        }
        if (task.getStatus() == null || !ACTIVE_STATUSES.contains(task.getStatus())) {
            return Evaluation.denied(Denial.TASK_INELIGIBLE);
        }
        if (CodexTaskService.CODEX_PROVIDER_TYPE.equals(task.getProviderType())
                && (task.getWorkerTaskId() == null || task.getWorkerTaskId().isBlank())) {
            return Evaluation.denied(Denial.TASK_INELIGIBLE);
        }
        if (task.getCreatedAtEpochMs() == null) {
            return Evaluation.denied(Denial.LEGACY_TASK_AGE_UNKNOWN);
        }
        if (task.getCreatedAtEpochMs() <= 0) {
            return Evaluation.denied(Denial.TASK_AGE_INVALID);
        }

        ResolvedBackgroundRecoveryPolicy resolved = resolve(task.getProviderType());
        if (resolved == null || !declaration.equals(resolved.declaration())) {
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
        Duration taskAge = Duration.between(createdAt, now);
        if (taskAge.compareTo(resolved.policy().bounds().recoveryWindow()) >= 0) {
            return Evaluation.denied(Denial.RECOVERY_WINDOW_EXHAUSTED);
        }
        return new Evaluation(Denial.NONE, resolved, taskAge);
    }

    private ResolvedBackgroundRecoveryPolicy resolve(String providerType) {
        BackgroundRecoveryCapabilityDeclaration declaration = DECLARATIONS.get(providerType);
        if (declaration == null) return null;
        try {
            return resolver.resolve(declaration);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean runtimeMatchesProvider(CodexTaskEntity task) {
        if (CodexTaskService.CODEX_PROVIDER_TYPE.equals(task.getProviderType())) {
            return CodexRuntimeType.SDK_EXEC.name().equals(task.getRuntimeType());
        }
        if (CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE.equals(task.getProviderType())) {
            return CodexRuntimeType.APP_SERVER.name().equals(task.getRuntimeType());
        }
        return false;
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

    private synchronized void release(String providerType) {
        int inFlight = inFlightByProvider.getOrDefault(providerType, 0);
        if (inFlight <= 1) {
            inFlightByProvider.remove(providerType);
        } else {
            inFlightByProvider.put(providerType, inFlight - 1);
        }
    }

    enum Denial {
        NONE,
        PROVIDER_UNDECLARED,
        PROVIDER_RUNTIME_MISMATCH,
        PROVIDER_IDENTITY_CHANGED,
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
        private final CodexBackgroundRecoveryPolicy owner;
        private final String providerType;
        private final int attempt;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private AttemptLease(
                CodexBackgroundRecoveryPolicy owner, String providerType, int attempt) {
            this.owner = owner;
            this.providerType = providerType;
            this.attempt = attempt;
        }

        int attempt() {
            return attempt;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(providerType);
            }
        }
    }

    private record RecoveryState(String providerType, int attempts) {
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
