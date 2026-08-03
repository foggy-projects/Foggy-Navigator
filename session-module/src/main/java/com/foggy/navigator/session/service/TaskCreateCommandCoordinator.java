package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.session.command.CommandOnceReceiptService;
import com.foggy.navigator.session.command.CommandOnceReceiptService.BeginEffectDisposition;
import com.foggy.navigator.session.command.CommandOnceReceiptService.ReceiptSnapshot;
import com.foggy.navigator.session.command.CommandOnceReceiptService.ReceiptState;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Narrow once-effect coordinator for an owner-proven task CREATE plan.
 *
 * <p>The coordinator owns receipt/replay semantics only. Target resolution, lifecycle reservation,
 * persistence and Provider routing remain in {@link TaskDispatchFacade} and
 * {@link TaskOperationRouter}.</p>
 */
@Service
public final class TaskCreateCommandCoordinator {

    static final String TASK_CREATE_ACTION = "task.create";
    static final String TASK_CREATED = "TASK_CREATED";
    static final String TASK_CREATE_OUTCOME_UNKNOWN = "TASK_CREATE_OUTCOME_UNKNOWN";

    private final TaskDispatchFacade taskDispatchFacade;
    private final CommandOnceReceiptService receiptService;

    public TaskCreateCommandCoordinator(
            TaskDispatchFacade taskDispatchFacade,
            CommandOnceReceiptService receiptService) {
        this.taskDispatchFacade = Objects.requireNonNull(
                taskDispatchFacade, "taskDispatchFacade must not be null");
        this.receiptService = Objects.requireNonNull(
                receiptService, "receiptService must not be null");
    }

    TaskCreateCommandResult execute(
            TaskDispatchRequest request,
            AgentResolveContext context,
            TaskCreateTargetResolver.CreateExecutionPlan plan,
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
        return execute(
                request,
                context,
                plan,
                envelope,
                decision,
                TaskCreateParticipants.NO_OP);
    }

    TaskCreateCommandResult execute(
            TaskDispatchRequest request,
            AgentResolveContext context,
            TaskCreateTargetResolver.CreateExecutionPlan plan,
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision,
            TaskCreateParticipants participants) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(plan, "create execution plan must not be null");
        Objects.requireNonNull(envelope, "command envelope must not be null");
        Objects.requireNonNull(decision, "authorization decision must not be null");
        Objects.requireNonNull(participants, "task create participants must not be null");

        plan.requireMatches(request, context);
        PlanBinding binding = PlanBinding.from(plan);
        binding.requireEnvelope(envelope);

        CommandOnceReceiptService.PrepareResult prepared =
                receiptService.prepare(envelope, decision);
        ReceiptSnapshot snapshot = Objects.requireNonNull(
                prepared.snapshot(), "receipt snapshot must not be null");
        if (snapshot.state() == ReceiptState.RESULT_RECORDED) {
            return new RecordedReplay(TaskReference.fromOpaque(snapshot.opaqueResultReference()));
        }
        if (snapshot.state() == ReceiptState.EFFECT_STARTED) {
            throw conflict("TASK_CREATE_EFFECT_ALREADY_STARTED");
        }
        if (snapshot.state() == ReceiptState.AMBIGUOUS) {
            throw conflict("TASK_CREATE_EFFECT_AMBIGUOUS");
        }
        if (snapshot.state() != ReceiptState.PREPARED) {
            throw conflict("TASK_CREATE_RECEIPT_STATE_CONFLICT");
        }

        ProviderEffectGate effectGate = new ProviderEffectGate(
                request,
                context,
                plan,
                binding,
                envelope,
                decision,
                participants,
                receiptService);
        try {
            DispatchTaskDTO dispatchedTask = taskDispatchFacade.createTask(
                    request, context, plan, effectGate);
            TaskReference reference = requireExactResult(dispatchedTask, plan);
            ResultIdentitySnapshot resultSnapshot =
                    ResultIdentitySnapshot.from(dispatchedTask);
            effectGate.completeFreshTask(dispatchedTask);
            TaskReference completedReference = requireExactResult(dispatchedTask, plan);
            resultSnapshot.requireUnchanged(dispatchedTask);
            if (!reference.equals(completedReference)) {
                throw conflict("TASK_CREATE_COMPLETION_RESULT_CONFLICT");
            }
            receiptService.recordResult(
                    envelope.binding().request().clientRequestId(),
                    effectGate.requireEffectAttemptId(),
                    completedReference.opaqueValue(),
                    TASK_CREATED);
            return new Executed(completedReference, dispatchedTask);
        } catch (RecordedResultReplay replay) {
            return new RecordedReplay(replay.reference());
        } catch (RuntimeException failure) {
            if (effectGate.providerEffectPermitted()) {
                markAmbiguous(envelope, effectGate, failure);
            }
            throw failure;
        }
    }

    private void markAmbiguous(
            CanonicalCommandEnvelope envelope,
            ProviderEffectGate effectGate,
            RuntimeException originalFailure) {
        try {
            receiptService.markAmbiguous(
                    envelope.binding().request().clientRequestId(),
                    effectGate.requireEffectAttemptId(),
                    TASK_CREATE_OUTCOME_UNKNOWN);
        } catch (RuntimeException ambiguousFailure) {
            if (ambiguousFailure != originalFailure) {
                originalFailure.addSuppressed(ambiguousFailure);
            }
        }
    }

    private static TaskReference requireExactResult(
            DispatchTaskDTO result,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        if (result == null) {
            throw conflict("TASK_CREATE_RESULT_MISSING");
        }
        TaskReference reference = new TaskReference(result.getTaskId());
        requireCompatibleResult("providerType", result.getProviderType(), plan.providerType());
        requireCompatibleResult("agentId", result.getAgentId(), plan.logicalAgentId());
        requireCompatibleResult("workerId", result.getWorkerId(), plan.physicalWorkerId());
        requireCompatibleResult("modelConfigId", result.getModelConfigId(), plan.modelConfigId());
        requireCompatibleResult("model", result.getModel(), plan.model());
        requireCompatibleResult("sessionId", result.getSessionId(), plan.sessionId());
        requireCompatibleResult("directoryId", result.getDirectoryId(), plan.directoryId());
        return reference;
    }

    private static void requireCompatibleResult(
            String field, @Nullable String actual, @Nullable String expected) {
        if (actual != null && !actual.isBlank() && !Objects.equals(actual, expected)) {
            throw conflict("TASK_CREATE_RESULT_"
                    + field.toUpperCase(Locale.ROOT) + "_CONFLICT");
        }
    }

    private static IllegalStateException conflict(String safeCode) {
        return new IllegalStateException(safeCode);
    }

    sealed interface TaskCreateCommandResult permits Executed, RecordedReplay {
        TaskReference reference();
    }

    record Executed(TaskReference reference, DispatchTaskDTO freshTask)
            implements TaskCreateCommandResult {
        Executed {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(freshTask, "freshTask must not be null");
        }
    }

    record RecordedReplay(TaskReference reference) implements TaskCreateCommandResult {
        RecordedReplay {
            Objects.requireNonNull(reference, "reference must not be null");
        }
    }

    record TaskReference(String taskId) {
        private static final String PREFIX = "TASK:";

        TaskReference {
            if (taskId == null || taskId.isBlank()) {
                throw conflict("TASK_CREATE_RESULT_TASK_ID_MISSING");
            }
            if (taskId.chars().anyMatch(Character::isISOControl)) {
                throw conflict("TASK_CREATE_RESULT_TASK_ID_INVALID");
            }
            if ((PREFIX + taskId).length() > 320) {
                throw conflict("TASK_CREATE_RESULT_TASK_ID_TOO_LONG");
            }
        }

        String opaqueValue() {
            return PREFIX + taskId;
        }

        static TaskReference fromOpaque(@Nullable String opaqueValue) {
            if (opaqueValue == null || !opaqueValue.startsWith(PREFIX)) {
                throw conflict("TASK_CREATE_RECORDED_REFERENCE_INVALID");
            }
            return new TaskReference(opaqueValue.substring(PREFIX.length()));
        }
    }

    record ResultIdentitySnapshot(
            String taskId,
            @Nullable String providerType,
            @Nullable String agentId,
            @Nullable String workerId,
            @Nullable String modelConfigId,
            @Nullable String model,
            @Nullable String sessionId,
            @Nullable String directoryId) {

        static ResultIdentitySnapshot from(DispatchTaskDTO result) {
            Objects.requireNonNull(result, "task result must not be null");
            return new ResultIdentitySnapshot(
                    result.getTaskId(),
                    result.getProviderType(),
                    result.getAgentId(),
                    result.getWorkerId(),
                    result.getModelConfigId(),
                    result.getModel(),
                    result.getSessionId(),
                    result.getDirectoryId());
        }

        void requireUnchanged(DispatchTaskDTO result) {
            if (!equals(from(result))) {
                throw conflict("TASK_CREATE_COMPLETION_RESULT_CONFLICT");
            }
        }
    }

    interface TaskCreateParticipants {
        TaskCreateParticipants NO_OP = new TaskCreateParticipants() {
            @Override
            public void prepareFreshTask() {
                // Compatibility lane: no participant work.
            }

            @Override
            public void completeFreshTask(DispatchTaskDTO freshTask) {
                // Compatibility lane: no participant work.
            }
        };

        void prepareFreshTask();

        void completeFreshTask(DispatchTaskDTO freshTask);
    }

    static final class PreparedProviderEffect<T> {
        private final ProviderEffectIdentity identity;
        private final Supplier<T> capturedEffect;

        private PreparedProviderEffect(
                ProviderEffectIdentity identity,
                Supplier<T> capturedEffect) {
            this.identity = Objects.requireNonNull(
                    identity, "provider effect identity must not be null");
            this.capturedEffect = Objects.requireNonNull(
                    capturedEffect, "captured provider effect must not be null");
        }

        static <I, T> PreparedProviderEffect<T> capture(
                ProviderEffectIdentity identity,
                I capturedInput,
                Function<I, T> providerEffect) {
            Objects.requireNonNull(capturedInput, "captured provider input must not be null");
            Objects.requireNonNull(providerEffect, "providerEffect must not be null");
            return new PreparedProviderEffect<>(
                    identity,
                    () -> providerEffect.apply(capturedInput));
        }

        ProviderEffectIdentity identity() {
            return identity;
        }

        T execute() {
            return capturedEffect.get();
        }
    }

    /** Single-use, coordinator-minted gate that structurally wraps the Provider callback. */
    static final class ProviderEffectGate {
        private final TaskDispatchRequest request;
        private final AgentResolveContext context;
        private final TaskCreateTargetResolver.CreateExecutionPlan expectedPlan;
        private final PlanBinding expectedBinding;
        private final CanonicalCommandEnvelope envelope;
        private final VerifiedCommandAuthorizationDecision decision;
        private final TaskCreateParticipants participants;
        private final CommandOnceReceiptService receiptService;

        private boolean invoked;
        private boolean providerEffectPermitted;
        private boolean providerEffectReturned;
        private boolean completionInvoked;
        @Nullable
        private String effectAttemptId;

        private ProviderEffectGate(
                TaskDispatchRequest request,
                AgentResolveContext context,
                TaskCreateTargetResolver.CreateExecutionPlan expectedPlan,
                PlanBinding expectedBinding,
                CanonicalCommandEnvelope envelope,
                VerifiedCommandAuthorizationDecision decision,
                TaskCreateParticipants participants,
                CommandOnceReceiptService receiptService) {
            this.request = request;
            this.context = context;
            this.expectedPlan = expectedPlan;
            this.expectedBinding = expectedBinding;
            this.envelope = envelope;
            this.decision = decision;
            this.participants = participants;
            this.receiptService = receiptService;
        }

        <T> T invoke(
                TaskCreateTargetResolver.CreateExecutionPlan actualPlan,
                ProviderEffectIdentity actualIdentity,
                Supplier<T> providerEffect) {
            Objects.requireNonNull(actualIdentity, "provider effect identity must not be null");
            Objects.requireNonNull(providerEffect, "providerEffect must not be null");
            if (participants != TaskCreateParticipants.NO_OP) {
                throw conflict("TASK_CREATE_FRESH_EFFECT_INPUT_REQUIRED");
            }
            return invokePrepared(
                    actualPlan,
                    () -> actualIdentity,
                    () -> PreparedProviderEffect.capture(
                            actualIdentity,
                            providerEffect,
                            Supplier::get));
        }

        <T> T invokePrepared(
                TaskCreateTargetResolver.CreateExecutionPlan actualPlan,
                Supplier<ProviderEffectIdentity> actualIdentitySupplier,
                Supplier<PreparedProviderEffect<T>> preparedProviderEffectSupplier) {
            return invokePrepared(
                    actualPlan,
                    actualIdentitySupplier,
                    () -> {
                        // Compatibility lane: no route preparation.
                    },
                    preparedProviderEffectSupplier);
        }

        <T> T invokePrepared(
                TaskCreateTargetResolver.CreateExecutionPlan actualPlan,
                Supplier<ProviderEffectIdentity> actualIdentitySupplier,
                Runnable routePreparation,
                Supplier<PreparedProviderEffect<T>> preparedProviderEffectSupplier) {
            Objects.requireNonNull(actualIdentitySupplier,
                    "provider effect identity supplier must not be null");
            Objects.requireNonNull(routePreparation,
                    "route preparation must not be null");
            Objects.requireNonNull(preparedProviderEffectSupplier,
                    "prepared provider effect supplier must not be null");
            synchronized (this) {
                if (invoked) {
                    throw conflict("TASK_CREATE_EFFECT_GATE_ALREADY_USED");
                }
                invoked = true;
                if (actualPlan != expectedPlan) {
                    throw conflict("TASK_CREATE_EFFECT_PLAN_CONFLICT");
                }
                expectedBinding.requireActual(requireActualIdentity(actualIdentitySupplier));
                CommandOnceReceiptService.EffectPermit permit =
                        receiptService.beginEffect(envelope, decision);
                if (permit.disposition() == BeginEffectDisposition.RESULT_RECORDED) {
                    throw new RecordedResultReplay(TaskReference.fromOpaque(
                            permit.snapshot().opaqueResultReference()));
                }
                if (permit.disposition() == BeginEffectDisposition.ALREADY_STARTED) {
                    throw conflict("TASK_CREATE_EFFECT_ALREADY_STARTED");
                }
                if (permit.disposition() == BeginEffectDisposition.AMBIGUOUS) {
                    throw conflict("TASK_CREATE_EFFECT_AMBIGUOUS");
                }
                if (!permit.providerEffectPermitted()) {
                    throw conflict("TASK_CREATE_EFFECT_NOT_PERMITTED");
                }
                effectAttemptId = permit.snapshot().effectAttemptId();
                if (effectAttemptId == null || effectAttemptId.isBlank()) {
                    providerEffectPermitted = true;
                    throw conflict("TASK_CREATE_EFFECT_ATTEMPT_MISSING");
                }
                providerEffectPermitted = true;
            }
            routePreparation.run();
            expectedPlan.requireMatches(request, context);
            expectedBinding.requireActual(requireActualIdentity(actualIdentitySupplier));
            participants.prepareFreshTask();
            expectedPlan.requireMatches(request, context);
            PreparedProviderEffect<T> preparedProviderEffect = Objects.requireNonNull(
                    preparedProviderEffectSupplier.get(),
                    "prepared provider effect must not be null");
            expectedBinding.requireActual(preparedProviderEffect.identity());
            T result = preparedProviderEffect.execute();
            synchronized (this) {
                providerEffectReturned = true;
            }
            return result;
        }

        private ProviderEffectIdentity requireActualIdentity(
                Supplier<ProviderEffectIdentity> actualIdentitySupplier) {
            return Objects.requireNonNull(
                    actualIdentitySupplier.get(),
                    "provider effect identity must not be null");
        }

        private void completeFreshTask(DispatchTaskDTO freshTask) {
            Objects.requireNonNull(freshTask, "fresh task must not be null");
            synchronized (this) {
                if (!providerEffectReturned) {
                    throw conflict("TASK_CREATE_EFFECT_RESULT_MISSING");
                }
                if (completionInvoked) {
                    throw conflict("TASK_CREATE_COMPLETION_ALREADY_USED");
                }
                completionInvoked = true;
            }
            participants.completeFreshTask(freshTask);
        }

        synchronized boolean providerEffectPermitted() {
            return providerEffectPermitted;
        }

        synchronized String requireEffectAttemptId() {
            if (!providerEffectPermitted || effectAttemptId == null || effectAttemptId.isBlank()) {
                throw conflict("TASK_CREATE_EFFECT_ATTEMPT_MISSING");
            }
            return effectAttemptId;
        }
    }

    record ProviderEffectIdentity(
            TaskCreateTargetResolver.ExecutionRoute executionRoute,
            @Nullable String tenantId,
            String ownerUserId,
            @Nullable String logicalAgentId,
            String providerType,
            @Nullable String physicalWorkerId,
            @Nullable String modelConfigId,
            @Nullable String model,
            @Nullable String sessionId,
            @Nullable String directoryId) {

        ProviderEffectIdentity {
            Objects.requireNonNull(executionRoute, "executionRoute must not be null");
            requireExactText(ownerUserId, "ownerUserId");
            requireExactText(providerType, "providerType");
        }

        static ProviderEffectIdentity atEffectPoint(
                TaskCreateTargetResolver.ExecutionRoute executionRoute,
                TaskDispatchRequest request,
                AgentResolveContext context,
                @Nullable String actualLogicalAgentId,
                String actualProviderType) {
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(context, "context must not be null");
            return new ProviderEffectIdentity(
                    executionRoute,
                    context.getTenantId(),
                    context.getUserId(),
                    actualLogicalAgentId,
                    actualProviderType,
                    request.getWorkerId(),
                    request.getModelConfigId(),
                    request.getModel(),
                    request.getSessionId(),
                    request.getDirectoryId());
        }
    }

    /** Exact plan-to-envelope binding shared by the later server-owned command factory. */
    static final class PlanBinding {
        private static final String DIGEST_DOMAIN = "navi.task-create-plan-binding.v1";
        private static final String DIGEST_VERSION = "LP_UTF8_SHA256_V1";
        private static final String SCOPE_PREFIX = "TASK_CREATE_SCOPE_" + DIGEST_VERSION + ":";
        private static final String TENANT_ABSENT = "navi.tenant.absent.v1";
        private static final String TENANT_PRESENT_PREFIX = "navi.tenant.present.v1:";

        private final ProviderEffectIdentity effectIdentity;
        private final String tenantReference;
        private final CanonicalCommandEnvelope.Target target;
        private final CanonicalCommandEnvelope.Effect effect;

        private PlanBinding(
                ProviderEffectIdentity effectIdentity,
                String tenantReference,
                CanonicalCommandEnvelope.Target target,
                CanonicalCommandEnvelope.Effect effect) {
            this.effectIdentity = effectIdentity;
            this.tenantReference = tenantReference;
            this.target = target;
            this.effect = effect;
        }

        static PlanBinding from(TaskCreateTargetResolver.CreateExecutionPlan plan) {
            Objects.requireNonNull(plan, "create execution plan must not be null");
            ProviderEffectIdentity identity = new ProviderEffectIdentity(
                    plan.executionRoute(),
                    plan.tenantId(),
                    plan.ownerUserId(),
                    plan.logicalAgentId(),
                    plan.providerType(),
                    plan.physicalWorkerId(),
                    plan.modelConfigId(),
                    plan.model(),
                    plan.sessionId(),
                    plan.directoryId());
            String tenantReference = tenantReference(identity.tenantId());
            requireEnvelopeReference(identity.ownerUserId(), "ownerUserId");
            String scope = scopeReference(identity);
            CanonicalCommandEnvelope.TargetKind kind = identity.logicalAgentId() != null
                    ? CanonicalCommandEnvelope.TargetKind.LOGICAL_AGENT
                    : CanonicalCommandEnvelope.TargetKind.RUNTIME;
            String targetId = identity.logicalAgentId() != null
                    ? identity.logicalAgentId()
                    : scope;
            CanonicalCommandEnvelope.Target target = new CanonicalCommandEnvelope.Target(
                    kind,
                    targetId,
                    identity.logicalAgentId(),
                    identity.providerType(),
                    identity.physicalWorkerId(),
                    identity.modelConfigId(),
                    null,
                    identity.sessionId());
            return new PlanBinding(
                    identity,
                    tenantReference,
                    target,
                    new CanonicalCommandEnvelope.Effect(TASK_CREATE_ACTION, scope));
        }

        void requireEnvelope(CanonicalCommandEnvelope envelope) {
            CanonicalCommandEnvelope.CommandBinding binding = envelope.binding();
            if (binding.commandKind() != CanonicalCommandEnvelope.CommandKind.CREATE) {
                throw conflict("TASK_CREATE_COMMAND_KIND_CONFLICT");
            }
            if (!tenantReference.equals(binding.ownership().tenantReference())
                    || !effectIdentity.ownerUserId().equals(
                    binding.ownership().ownerReference())) {
                throw conflict("TASK_CREATE_OWNERSHIP_CONFLICT");
            }
            if (!target.equals(binding.target())) {
                throw conflict("TASK_CREATE_TARGET_CONFLICT");
            }
            if (!effect.equals(binding.effect())) {
                throw conflict("TASK_CREATE_EFFECT_BINDING_CONFLICT");
            }
        }

        void requireActual(ProviderEffectIdentity actualIdentity) {
            if (!effectIdentity.equals(actualIdentity)) {
                throw conflict("TASK_CREATE_EFFECT_IDENTITY_CONFLICT");
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

        private static String tenantReference(@Nullable String tenantId) {
            String reference = tenantId == null
                    ? TENANT_ABSENT
                    : TENANT_PRESENT_PREFIX + tenantId;
            requireEnvelopeReference(reference, "tenantReference");
            return reference;
        }

        private static String scopeReference(ProviderEffectIdentity identity) {
            PlanDigest digest = new PlanDigest(DIGEST_DOMAIN)
                    .field(identity.tenantId())
                    .field(identity.ownerUserId())
                    .field(identity.logicalAgentId())
                    .field(identity.providerType())
                    .field(identity.physicalWorkerId())
                    .field(identity.modelConfigId())
                    .field(identity.model())
                    .field(identity.sessionId())
                    .field(identity.directoryId())
                    .field(identity.executionRoute().name());
            return SCOPE_PREFIX + digest.finish();
        }

        private static void requireEnvelopeReference(String value, String field) {
            if (value.length() > CanonicalCommandEnvelope.MAX_REFERENCE_LENGTH) {
                throw new IllegalArgumentException(
                        field + " exceeds " + CanonicalCommandEnvelope.MAX_REFERENCE_LENGTH
                                + " characters");
            }
            if (value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(field + " contains control characters");
            }
        }
    }

    private static void requireExactText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
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
                digest.update((byte) 0);
                digest.update((byte) 0);
                digest.update((byte) 0);
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

    private static final class RecordedResultReplay extends RuntimeException {
        private final TaskReference reference;

        private RecordedResultReplay(TaskReference reference) {
            super("TASK_CREATE_RESULT_RECORDED");
            this.reference = reference;
        }

        private TaskReference reference() {
            return reference;
        }
    }
}
