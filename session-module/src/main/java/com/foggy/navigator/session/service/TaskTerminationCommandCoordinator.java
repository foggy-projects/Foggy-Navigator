package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.CanonicalCommandReceiptPort;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Provider-neutral once-effect guard for an exact, owner-proven Task termination plan.
 *
 * <p>The receipt records only a command attempt. Provider termination operations, lifecycle
 * intent/outbox/fence, Worker acknowledgements, observations, and canonical terminal commits keep
 * their existing ownership.</p>
 */
@Service
final class TaskTerminationCommandCoordinator {

    static final String TASK_TERMINATE_ACTION = "task.terminate";
    static final String TERMINATION_REQUEST_ACCEPTED = "TERMINATION_REQUEST_ACCEPTED";
    static final String TERMINATION_OUTCOME_UNKNOWN = "TERMINATION_OUTCOME_UNKNOWN";
    static final String TERMINATION_EFFECT_AMBIGUOUS = "TERMINATION_EFFECT_AMBIGUOUS";
    private static final String ALREADY_TERMINAL_PREFIX = "TASK_ALREADY_TERMINAL_";
    private static final Set<String> TERMINAL_STATES = Set.of("COMPLETED", "FAILED", "ABORTED");

    private final TaskDispatchFacade taskDispatchFacade;
    private final CanonicalCommandReceiptPort receipts;

    TaskTerminationCommandCoordinator(
            TaskDispatchFacade taskDispatchFacade,
            CanonicalCommandReceiptPort receipts) {
        this.taskDispatchFacade = Objects.requireNonNull(
                taskDispatchFacade, "taskDispatchFacade must not be null");
        this.receipts = Objects.requireNonNull(receipts, "receipts must not be null");
    }

    static String canonicalTenantReference(@Nullable String tenantId) {
        String reference = tenantId == null
                ? PlanBinding.TENANT_ABSENT
                : PlanBinding.TENANT_PRESENT_PREFIX + tenantId;
        if (reference.length() > CanonicalCommandEnvelope.MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("tenantReference exceeds maximum length");
        }
        return reference;
    }

    TerminationCommandResult execute(
            TerminationExecutionPlan plan,
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
        Objects.requireNonNull(plan, "termination plan must not be null");
        Objects.requireNonNull(envelope, "command envelope must not be null");
        Objects.requireNonNull(decision, "authorization decision must not be null");
        PlanBinding binding = PlanBinding.from(plan);
        binding.requireEnvelope(envelope);

        CanonicalCommandReceiptPort.PrepareResult prepared = receipts.prepare(envelope, decision);
        CanonicalCommandReceiptPort.ReceiptSnapshot snapshot = Objects.requireNonNull(
                prepared.snapshot(), "receipt snapshot must not be null");
        TerminationCommandResult recorded = recorded(plan, snapshot);
        if (recorded != null) {
            return recorded;
        }
        requirePrepared(snapshot);

        TerminationEffectGate effectGate = new TerminationEffectGate(
                plan, envelope, decision, receipts);
        try {
            Outcome outcome = Objects.requireNonNull(
                    taskDispatchFacade.executeTerminationPlan(plan, effectGate),
                    "termination outcome must not be null");
            TaskReference reference = new TaskReference(plan.identity().taskId());
            CanonicalCommandReceiptPort.ReceiptSnapshot completed = receipts.recordResult(
                    envelope.binding().request().clientRequestId(),
                    effectGate.requireEffectAttemptId(),
                    reference.opaqueValue(),
                    outcome.safeCode());
            requireRecorded(completed, reference, outcome);
            return new Executed(reference, outcome);
        } catch (RecordedResultReplay replay) {
            return replay.result();
        } catch (RuntimeException failure) {
            if (effectGate.effectAttemptStarted()) {
                markAmbiguous(
                        envelope,
                        effectGate.requireEffectAttemptId(),
                        failure);
                throw new IllegalStateException(
                        TERMINATION_EFFECT_AMBIGUOUS, failure);
            }
            throw failure;
        }
    }

    private void markAmbiguous(
            CanonicalCommandEnvelope envelope,
            String attemptId,
            RuntimeException originalFailure) {
        try {
            receipts.markAmbiguous(
                    envelope.binding().request().clientRequestId(),
                    attemptId,
                    TERMINATION_OUTCOME_UNKNOWN);
        } catch (RuntimeException markFailure) {
            if (markFailure != originalFailure) {
                originalFailure.addSuppressed(markFailure);
            }
        }
    }

    @Nullable
    private static TerminationCommandResult recorded(
            TerminationExecutionPlan plan,
            CanonicalCommandReceiptPort.ReceiptSnapshot snapshot) {
        Objects.requireNonNull(plan, "termination plan must not be null");
        Objects.requireNonNull(snapshot, "receipt snapshot must not be null");
        if (snapshot.state() == CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED) {
            TaskReference reference = TaskReference.fromOpaque(
                    snapshot.opaqueResultReference());
            if (!plan.identity().taskId().equals(reference.taskId())) {
                throw conflict("TERMINATION_RECORDED_TASK_CONFLICT");
            }
            return new RecordedReplay(reference, Outcome.fromSafeCode(snapshot.safeCode()));
        }
        return null;
    }

    private static void requirePrepared(CanonicalCommandReceiptPort.ReceiptSnapshot snapshot) {
        if (snapshot.state() == CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED) {
            throw conflict(TERMINATION_EFFECT_AMBIGUOUS);
        }
        if (snapshot.state() == CanonicalCommandReceiptPort.ReceiptState.AMBIGUOUS) {
            throw conflict(TERMINATION_EFFECT_AMBIGUOUS);
        }
        if (snapshot.state() != CanonicalCommandReceiptPort.ReceiptState.PREPARED) {
            throw conflict("TERMINATION_RECEIPT_STATE_CONFLICT");
        }
    }

    private static void requireRecorded(
            CanonicalCommandReceiptPort.ReceiptSnapshot snapshot,
            TaskReference reference,
            Outcome outcome) {
        if (snapshot == null
                || snapshot.state() != CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED
                || !Objects.equals(reference.opaqueValue(), snapshot.opaqueResultReference())
                || !Objects.equals(outcome.safeCode(), snapshot.safeCode())) {
            throw conflict("TERMINATION_RESULT_RECORD_CONFLICT");
        }
    }

    sealed interface TerminationCommandResult permits Executed, RecordedReplay {
        TaskReference reference();

        Outcome outcome();
    }

    record Executed(TaskReference reference, Outcome outcome)
            implements TerminationCommandResult {
        Executed {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    record RecordedReplay(TaskReference reference, Outcome outcome)
            implements TerminationCommandResult {
        RecordedReplay {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    record TaskReference(String taskId) {
        private static final String PREFIX = "TASK:";

        TaskReference {
            requireReference(taskId, "taskId");
            if ((PREFIX + taskId).length() > 320) {
                throw conflict("TERMINATION_RESULT_TASK_ID_TOO_LONG");
            }
        }

        String opaqueValue() {
            return PREFIX + taskId;
        }

        static TaskReference fromOpaque(@Nullable String value) {
            if (value == null || !value.startsWith(PREFIX)) {
                throw conflict("TERMINATION_RECORDED_REFERENCE_INVALID");
            }
            return new TaskReference(value.substring(PREFIX.length()));
        }
    }

    record Outcome(String safeCode, @Nullable String terminalStatus) {
        Outcome {
            requireReference(safeCode, "safeCode");
            if (terminalStatus == null) {
                if (!TERMINATION_REQUEST_ACCEPTED.equals(safeCode)) {
                    throw conflict("TERMINATION_OUTCOME_CODE_INVALID");
                }
            } else if (!TERMINAL_STATES.contains(terminalStatus)
                    || !(ALREADY_TERMINAL_PREFIX + terminalStatus).equals(safeCode)) {
                throw conflict("TERMINATION_OUTCOME_CODE_INVALID");
            }
        }

        static Outcome accepted() {
            return new Outcome(TERMINATION_REQUEST_ACCEPTED, null);
        }

        static Outcome alreadyTerminal(String status) {
            return new Outcome(ALREADY_TERMINAL_PREFIX + status, status);
        }

        static Outcome fromSafeCode(@Nullable String safeCode) {
            if (TERMINATION_REQUEST_ACCEPTED.equals(safeCode)) {
                return accepted();
            }
            if (safeCode != null && safeCode.startsWith(ALREADY_TERMINAL_PREFIX)) {
                return alreadyTerminal(safeCode.substring(ALREADY_TERMINAL_PREFIX.length()));
            }
            throw conflict("TERMINATION_RECORDED_OUTCOME_INVALID");
        }
    }

    /** Captured provider/A2A callback whose invocation is private to the coordinator gate. */
    static final class CapturedTerminationEffect {
        private final Supplier<Outcome> callback;

        CapturedTerminationEffect(Supplier<Outcome> callback) {
            this.callback = Objects.requireNonNull(
                    callback, "termination callback must not be null");
        }

        private Outcome execute() {
            return Objects.requireNonNull(
                    callback.get(), "termination callback outcome must not be null");
        }
    }

    enum ExecutionRoute {
        PROVIDER,
        A2A
    }

    record TerminationIdentity(
            String taskId,
            String ownerUserId,
            @Nullable String tenantId,
            String sessionId,
            @Nullable String providerTaskId,
            @Nullable String logicalAgentId,
            @Nullable String providerType,
            @Nullable String physicalWorkerId,
            @Nullable String directoryId,
            @Nullable String model,
            @Nullable String modelConfigId,
            @Nullable String runtimeId,
            @Nullable Integer runtimeRevision,
            @Nullable String runtimeType,
            @Nullable String runtimeInstanceId,
            @Nullable Long routingEpoch,
            ExecutionRoute executionRoute,
            boolean force) {
        TerminationIdentity {
            requireReference(taskId, "taskId");
            requireReference(ownerUserId, "ownerUserId");
            requireReference(sessionId, "sessionId");
            Objects.requireNonNull(executionRoute, "executionRoute must not be null");
            if (executionRoute == ExecutionRoute.PROVIDER) {
                requireReference(providerType, "providerType");
            } else {
                if (providerType != null && !providerType.isBlank()) {
                    throw conflict("TERMINATION_A2A_PROVIDER_CONFLICT");
                }
            }
        }

        static TerminationIdentity from(
                DispatchTaskDTO task,
                AgentResolveContext context,
                boolean force) {
            Objects.requireNonNull(task, "task must not be null");
            Objects.requireNonNull(context, "resolve context must not be null");
            String provider = task.getProviderType();
            ExecutionRoute route = provider != null && !provider.isBlank()
                    ? ExecutionRoute.PROVIDER
                    : ExecutionRoute.A2A;
            return new TerminationIdentity(
                    task.getTaskId(),
                    context.getUserId(),
                    context.getTenantId(),
                    task.getSessionId(),
                    task.getWorkerTaskId(),
                    task.getAgentId(),
                    provider,
                    task.getWorkerId(),
                    task.getDirectoryId(),
                    task.getModel(),
                    task.getModelConfigId(),
                    task.getRuntimeId(),
                    task.getRuntimeRevision(),
                    task.getRuntimeType(),
                    task.getRuntimeInstanceId(),
                    task.getRoutingEpoch(),
                    route,
                    force);
        }
    }

    static final class TerminationExecutionPlan {
        private final TerminationIdentity identity;
        private final AgentResolveContext context;
        @Nullable
        private final String initiallyTerminalStatus;
        @Nullable
        private final CapturedTerminationEffect capturedEffect;

        TerminationExecutionPlan(
                TerminationIdentity identity,
                AgentResolveContext context,
                @Nullable String initiallyTerminalStatus,
                @Nullable CapturedTerminationEffect capturedEffect) {
            this.identity = Objects.requireNonNull(identity, "identity must not be null");
            this.context = copyContext(context);
            if (initiallyTerminalStatus != null
                    && !TERMINAL_STATES.contains(initiallyTerminalStatus)) {
                throw conflict("TERMINATION_INITIAL_STATUS_INVALID");
            }
            this.initiallyTerminalStatus = initiallyTerminalStatus;
            this.capturedEffect = capturedEffect;
            if (initiallyTerminalStatus == null && capturedEffect == null) {
                throw conflict("TERMINATION_ROUTE_CAPTURE_MISSING");
            }
            if (initiallyTerminalStatus != null && capturedEffect != null) {
                throw conflict("TERMINATION_TERMINAL_EFFECT_CONFLICT");
            }
        }

        TerminationIdentity identity() {
            return identity;
        }

        AgentResolveContext context() {
            return context;
        }

        @Nullable
        String initiallyTerminalStatus() {
            return initiallyTerminalStatus;
        }

        private Outcome executeCapturedEffect() {
            CapturedTerminationEffect effect = Objects.requireNonNull(
                    capturedEffect, "captured termination effect is unavailable");
            return effect.execute();
        }

        @Override
        public String toString() {
            return "TerminationExecutionPlan[content-free]";
        }
    }

    /** Single-use coordinator-minted gate around receipt permit and the captured callback. */
    static final class TerminationEffectGate {
        private final TerminationExecutionPlan expectedPlan;
        private final CanonicalCommandEnvelope envelope;
        private final VerifiedCommandAuthorizationDecision decision;
        private final CanonicalCommandReceiptPort receipts;

        private boolean invoked;
        private boolean effectAttemptStarted;
        @Nullable
        private String effectAttemptId;

        private TerminationEffectGate(
                TerminationExecutionPlan expectedPlan,
                CanonicalCommandEnvelope envelope,
                VerifiedCommandAuthorizationDecision decision,
                CanonicalCommandReceiptPort receipts) {
            this.expectedPlan = expectedPlan;
            this.envelope = envelope;
            this.decision = decision;
            this.receipts = receipts;
        }

        Outcome invoke(
                TerminationExecutionPlan actualPlan,
                Supplier<String> currentTerminalStatusSupplier) {
            Objects.requireNonNull(
                    currentTerminalStatusSupplier,
                    "termination status supplier must not be null");
            synchronized (this) {
                if (invoked) {
                    throw conflict("TERMINATION_EFFECT_GATE_ALREADY_USED");
                }
                invoked = true;
                if (actualPlan != expectedPlan) {
                    throw conflict("TERMINATION_EFFECT_PLAN_CONFLICT");
                }
                CanonicalCommandReceiptPort.EffectPermit permit =
                        receipts.beginEffect(envelope, decision);
                CanonicalCommandReceiptPort.ReceiptSnapshot snapshot =
                        Objects.requireNonNull(
                                permit.snapshot(),
                                "effect permit snapshot must not be null");
                TerminationCommandResult recorded = recorded(expectedPlan, snapshot);
                if (recorded != null) {
                    throw new RecordedResultReplay(recorded);
                }
                if (permit.disposition()
                        == CanonicalCommandReceiptPort.BeginEffectDisposition.ALREADY_STARTED) {
                    throw conflict(TERMINATION_EFFECT_AMBIGUOUS);
                }
                if (permit.disposition()
                        == CanonicalCommandReceiptPort.BeginEffectDisposition.AMBIGUOUS) {
                    throw conflict(TERMINATION_EFFECT_AMBIGUOUS);
                }
                if (!permit.providerEffectPermitted()) {
                    throw conflict("TERMINATION_EFFECT_NOT_PERMITTED");
                }
                effectAttemptId = snapshot.effectAttemptId();
                if (effectAttemptId == null || effectAttemptId.isBlank()) {
                    throw conflict("TERMINATION_EFFECT_ATTEMPT_MISSING");
                }
                effectAttemptStarted = true;
            }

            String terminalStatus = currentTerminalStatusSupplier.get();
            if (terminalStatus != null) {
                return Outcome.alreadyTerminal(terminalStatus);
            }
            return expectedPlan.executeCapturedEffect();
        }

        private synchronized boolean effectAttemptStarted() {
            return effectAttemptStarted;
        }

        private synchronized String requireEffectAttemptId() {
            if (!effectAttemptStarted
                    || effectAttemptId == null
                    || effectAttemptId.isBlank()) {
                throw conflict("TERMINATION_EFFECT_ATTEMPT_MISSING");
            }
            return effectAttemptId;
        }
    }

    private static final class RecordedResultReplay extends RuntimeException {
        private final TerminationCommandResult result;

        private RecordedResultReplay(TerminationCommandResult result) {
            super("TERMINATION_RESULT_RECORDED");
            this.result = Objects.requireNonNull(result, "recorded result must not be null");
        }

        private TerminationCommandResult result() {
            return result;
        }
    }

    /** Exact plan-to-envelope binding shared by the later trusted HTTP adapters. */
    static final class PlanBinding {
        private static final String DIGEST_DOMAIN = "navi.task-termination-plan-binding.v1";
        private static final String DIGEST_VERSION = "LP_UTF8_SHA256_V1";
        private static final String SCOPE_PREFIX =
                "TASK_TERMINATE_SCOPE_" + DIGEST_VERSION + ":";
        private static final String TENANT_ABSENT = "navi.tenant.absent.v1";
        private static final String TENANT_PRESENT_PREFIX = "navi.tenant.present.v1:";

        private final TerminationIdentity identity;
        private final String tenantReference;
        private final CanonicalCommandEnvelope.Target target;
        private final CanonicalCommandEnvelope.Effect effect;

        private PlanBinding(
                TerminationIdentity identity,
                String tenantReference,
                CanonicalCommandEnvelope.Target target,
                CanonicalCommandEnvelope.Effect effect) {
            this.identity = identity;
            this.tenantReference = tenantReference;
            this.target = target;
            this.effect = effect;
        }

        static PlanBinding from(TerminationExecutionPlan plan) {
            Objects.requireNonNull(plan, "termination plan must not be null");
            TerminationIdentity identity = plan.identity();
            String tenantReference = canonicalTenantReference(identity.tenantId());
            CanonicalCommandEnvelope.Target target = new CanonicalCommandEnvelope.Target(
                    CanonicalCommandEnvelope.TargetKind.TASK,
                    identity.taskId(),
                    identity.logicalAgentId(),
                    identity.providerType(),
                    identity.physicalWorkerId(),
                    identity.modelConfigId(),
                    identity.taskId(),
                    identity.sessionId());
            CanonicalCommandEnvelope.Effect effect = new CanonicalCommandEnvelope.Effect(
                    TASK_TERMINATE_ACTION,
                    scopeReference(identity));
            return new PlanBinding(identity, tenantReference, target, effect);
        }

        void requireEnvelope(CanonicalCommandEnvelope envelope) {
            CanonicalCommandEnvelope.CommandBinding binding = envelope.binding();
            if (binding.commandKind() != CanonicalCommandEnvelope.CommandKind.TERMINATE) {
                throw conflict("TERMINATION_COMMAND_KIND_CONFLICT");
            }
            if (!tenantReference.equals(binding.ownership().tenantReference())
                    || !identity.ownerUserId().equals(binding.ownership().ownerReference())
                    || binding.ownership().clientAppReference() != null
                    || binding.ownership().upstreamReference() != null) {
                throw conflict("TERMINATION_OWNERSHIP_CONFLICT");
            }
            if (!target.equals(binding.target())) {
                throw conflict("TERMINATION_TARGET_CONFLICT");
            }
            if (!effect.equals(binding.effect())) {
                throw conflict("TERMINATION_EFFECT_BINDING_CONFLICT");
            }
        }

        String tenantReference() {
            return tenantReference;
        }

        CanonicalCommandEnvelope.Target target() {
            return target;
        }

        CanonicalCommandEnvelope.Effect effect() {
            return effect;
        }

        private static String scopeReference(TerminationIdentity identity) {
            PlanDigest digest = new PlanDigest(DIGEST_DOMAIN)
                    .field(identity.ownerUserId())
                    .field(identity.tenantId())
                    .field(identity.taskId())
                    .field(identity.sessionId())
                    .field(identity.providerTaskId())
                    .field(identity.logicalAgentId())
                    .field(identity.providerType())
                    .field(identity.physicalWorkerId())
                    .field(identity.directoryId())
                    .field(identity.model())
                    .field(identity.modelConfigId())
                    .field(identity.runtimeId())
                    .field(integer(identity.runtimeRevision()))
                    .field(identity.runtimeType())
                    .field(identity.runtimeInstanceId())
                    .field(longValue(identity.routingEpoch()))
                    .field(identity.executionRoute().name())
                    .field(Boolean.toString(identity.force()));
            return SCOPE_PREFIX + digest.finish();
        }
    }

    private static AgentResolveContext copyContext(AgentResolveContext context) {
        Objects.requireNonNull(context, "resolve context must not be null");
        return AgentResolveContext.builder()
                .userId(context.getUserId())
                .tenantId(context.getTenantId())
                .sessionId(context.getSessionId())
                .modelConfigId(context.getModelConfigId())
                .requestSource(context.getRequestSource())
                .build();
    }

    private static String integer(@Nullable Integer value) {
        return value == null ? null : Integer.toString(value);
    }

    private static String longValue(@Nullable Long value) {
        return value == null ? null : Long.toString(value);
    }

    private static void requireReference(@Nullable String value, String field) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw conflict("TERMINATION_" + field.toUpperCase() + "_INVALID");
        }
    }

    private static IllegalStateException conflict(String safeCode) {
        return new IllegalStateException(safeCode);
    }

    private static final class PlanDigest {
        private final MessageDigest digest;

        private PlanDigest(String domain) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("SHA-256 is unavailable", unavailable);
            }
            field(domain);
        }

        private PlanDigest field(@Nullable String value) {
            if (value == null) {
                digest.update((byte) 0);
                return this;
            }
            digest.update((byte) 1);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
            return this;
        }

        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
