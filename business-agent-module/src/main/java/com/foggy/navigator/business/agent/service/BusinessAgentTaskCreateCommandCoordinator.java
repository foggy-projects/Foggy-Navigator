package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.CanonicalCommandReceiptPort;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Business-only once-effect coordinator for one immutable fresh Task plan. */
@Service
public final class BusinessAgentTaskCreateCommandCoordinator {

    static final String BUSINESS_TASK_CREATE_ACTION = "business-task.create";
    static final String BUSINESS_TASK_CREATED = "BUSINESS_TASK_CREATED";
    static final String BUSINESS_TASK_CREATE_OUTCOME_UNKNOWN =
            "BUSINESS_TASK_CREATE_OUTCOME_UNKNOWN";

    private final CanonicalCommandReceiptPort receiptPort;
    private final BusinessAgentTaskService taskService;

    public BusinessAgentTaskCreateCommandCoordinator(
            CanonicalCommandReceiptPort receiptPort,
            BusinessAgentTaskService taskService) {
        this.receiptPort = Objects.requireNonNull(receiptPort, "receiptPort must not be null");
        this.taskService = Objects.requireNonNull(taskService, "taskService must not be null");
    }

    BusinessTaskCreateCommandResult execute(
            BusinessAgentTaskPreparedFreshCreate prepared,
            CanonicalCommandEnvelope envelope,
            VerifiedCommandAuthorizationDecision decision) {
        Objects.requireNonNull(prepared, "prepared must not be null");
        Objects.requireNonNull(envelope, "envelope must not be null");
        Objects.requireNonNull(decision, "decision must not be null");

        PlanBinding binding = PlanBinding.from(prepared);
        binding.requireEnvelope(envelope);
        String clientRequestId = envelope.binding().request().clientRequestId();

        CanonicalCommandReceiptPort.PrepareResult preparedReceipt = Objects.requireNonNull(
                receiptPort.prepare(envelope, decision),
                "receipt prepare result must not be null");
        CanonicalCommandReceiptPort.ReceiptSnapshot preparedSnapshot = requireSnapshot(
                preparedReceipt.snapshot(), clientRequestId);
        if (preparedSnapshot.state() == CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED) {
            return new RecordedReplay(recordedReference(preparedSnapshot, clientRequestId));
        }
        if (preparedSnapshot.state() == CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED) {
            throw conflict("BUSINESS_TASK_CREATE_EFFECT_ALREADY_STARTED");
        }
        if (preparedSnapshot.state() == CanonicalCommandReceiptPort.ReceiptState.AMBIGUOUS) {
            throw conflict("BUSINESS_TASK_CREATE_EFFECT_AMBIGUOUS");
        }
        if (preparedSnapshot.state() != CanonicalCommandReceiptPort.ReceiptState.PREPARED) {
            throw conflict("BUSINESS_TASK_CREATE_RECEIPT_STATE_CONFLICT");
        }

        String effectAttemptId = null;
        boolean effectPermitted = false;
        try {
            CanonicalCommandReceiptPort.EffectPermit permit = Objects.requireNonNull(
                    receiptPort.beginEffect(envelope, decision),
                    "receipt effect permit must not be null");
            CanonicalCommandReceiptPort.BeginEffectDisposition disposition =
                    Objects.requireNonNull(
                            permit.disposition(), "receipt effect disposition must not be null");
            CanonicalCommandReceiptPort.ReceiptSnapshot permitSnapshot = requireSnapshot(
                    permit.snapshot(), clientRequestId);
            if (disposition
                    == CanonicalCommandReceiptPort.BeginEffectDisposition.RESULT_RECORDED) {
                return new RecordedReplay(recordedReference(permitSnapshot, clientRequestId));
            }
            if (disposition
                    == CanonicalCommandReceiptPort.BeginEffectDisposition.ALREADY_STARTED) {
                throw conflict("BUSINESS_TASK_CREATE_EFFECT_ALREADY_STARTED");
            }
            if (disposition == CanonicalCommandReceiptPort.BeginEffectDisposition.AMBIGUOUS) {
                throw conflict("BUSINESS_TASK_CREATE_EFFECT_AMBIGUOUS");
            }
            if (disposition != CanonicalCommandReceiptPort.BeginEffectDisposition.PERMITTED
                    || permitSnapshot.state()
                    != CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED) {
                throw conflict("BUSINESS_TASK_CREATE_EFFECT_NOT_PERMITTED");
            }
            effectAttemptId = requireText(
                    permitSnapshot.effectAttemptId(),
                    "BUSINESS_TASK_CREATE_EFFECT_ATTEMPT_MISSING");
            effectPermitted = true;

            CreatedBusinessAgentTaskDTO freshTask = taskService.executeFreshCreatePlan(prepared);
            BusinessTaskReference reference = requireExactResult(freshTask, prepared.plan());
            CanonicalCommandReceiptPort.ReceiptSnapshot recorded = receiptPort.recordResult(
                    clientRequestId,
                    effectAttemptId,
                    reference.opaqueValue(),
                    BUSINESS_TASK_CREATED);
            requireRecordedResult(recorded, clientRequestId, effectAttemptId, reference);
            return new Executed(reference, freshTask);
        } catch (RuntimeException failure) {
            if (effectPermitted) {
                markAmbiguous(clientRequestId, effectAttemptId, failure);
            }
            throw failure;
        }
    }

    private void markAmbiguous(
            String clientRequestId,
            String effectAttemptId,
            RuntimeException originalFailure) {
        try {
            receiptPort.markAmbiguous(
                    clientRequestId,
                    effectAttemptId,
                    BUSINESS_TASK_CREATE_OUTCOME_UNKNOWN);
        } catch (RuntimeException ambiguousFailure) {
            if (ambiguousFailure != originalFailure) {
                originalFailure.addSuppressed(ambiguousFailure);
            }
        }
    }

    private static CanonicalCommandReceiptPort.ReceiptSnapshot requireSnapshot(
            @Nullable CanonicalCommandReceiptPort.ReceiptSnapshot snapshot,
            String expectedClientRequestId) {
        if (snapshot == null) {
            throw conflict("BUSINESS_TASK_CREATE_RECEIPT_SNAPSHOT_MISSING");
        }
        if (!expectedClientRequestId.equals(snapshot.clientRequestId())) {
            throw conflict("BUSINESS_TASK_CREATE_RECEIPT_REQUEST_CONFLICT");
        }
        if (snapshot.state() == null) {
            throw conflict("BUSINESS_TASK_CREATE_RECEIPT_STATE_MISSING");
        }
        return snapshot;
    }

    private static BusinessTaskReference recordedReference(
            CanonicalCommandReceiptPort.ReceiptSnapshot snapshot,
            String expectedClientRequestId) {
        requireSnapshot(snapshot, expectedClientRequestId);
        if (snapshot.state() != CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED
                || !BUSINESS_TASK_CREATED.equals(snapshot.safeCode())
                || snapshot.effectAttemptId() == null
                || snapshot.effectAttemptId().isBlank()) {
            throw conflict("BUSINESS_TASK_CREATE_RECORDED_RESULT_CONFLICT");
        }
        return BusinessTaskReference.fromOpaque(snapshot.opaqueResultReference());
    }

    private static void requireRecordedResult(
            @Nullable CanonicalCommandReceiptPort.ReceiptSnapshot snapshot,
            String clientRequestId,
            String effectAttemptId,
            BusinessTaskReference reference) {
        CanonicalCommandReceiptPort.ReceiptSnapshot recorded = requireSnapshot(
                snapshot, clientRequestId);
        if (recorded.state() != CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED
                || !effectAttemptId.equals(recorded.effectAttemptId())
                || !reference.opaqueValue().equals(recorded.opaqueResultReference())
                || !BUSINESS_TASK_CREATED.equals(recorded.safeCode())) {
            throw conflict("BUSINESS_TASK_CREATE_RECORDED_RESULT_CONFLICT");
        }
    }

    private static BusinessTaskReference requireExactResult(
            @Nullable CreatedBusinessAgentTaskDTO result,
            BusinessAgentTaskCreatePlan plan) {
        if (result == null) {
            throw conflict("BUSINESS_TASK_CREATE_RESULT_MISSING");
        }
        BusinessAgentTaskCreatePlan.Identity identity = plan.identity();
        BusinessAgentTaskCreatePlan.AgentRoute route = plan.agentRoute();
        BusinessAgentTaskCreatePlan.ModelTarget model = plan.modelTarget();
        BusinessAgentTaskCreatePlan.WorkspaceTarget workspace = plan.workspaceTarget();
        BusinessAgentTaskCreatePlan.InputBinding input = plan.inputBinding();

        BusinessTaskReference reference = new BusinessTaskReference(result.getTaskId());
        requireExact(result.getTenantId(), identity.tenantId(), "TENANT");
        requireExact(result.getNavigatorEffectiveUserId(), identity.actorUserId(), "ACTOR");
        requireExact(result.getClientAppId(), identity.clientAppId(), "CLIENT_APP");
        requireExact(result.getUpstreamUserId(), identity.upstreamUserId(), "UPSTREAM_USER");
        requireExact(result.getSessionId(), identity.sessionId(), "SESSION");
        requireExact(result.getAgentId(), route.agentId(), "AGENT");
        requireExact(result.getSkillId(), route.skillId(), "SKILL");
        requireExact(result.getWorkerPoolId(), route.internalWorkerRouteId(), "WORKER_ROUTE");
        requireExact(
                result.getDirectoryId(),
                workspace != null ? workspace.directoryId() : null,
                "DIRECTORY");
        requireExact(result.getModelConfigId(), model.modelConfigId(), "MODEL_CONFIG");
        requireExact(result.getModel(), model.modelName(), "MODEL");
        requireExact(
                result.getRequestedModelConfigId(),
                input.requestedModelConfigIdRaw(),
                "REQUESTED_MODEL_CONFIG");
        requireExact(
                result.getRequestedModelVariant(),
                trimToNull(input.requestedModelVariant()),
                "REQUESTED_MODEL_VARIANT");
        requireExact(result.getStatus(), BusinessAgentTaskService.STATUS_CREATED, "STATUS");

        if (route.launcherType() == null) {
            requireExact(result.getWorkerId(), null, "WORKER");
            requireExact(result.getWorkerProviderType(), null, "PROVIDER");
            requireExact(result.getWorkerTaskId(), null, "WORKER_TASK");
            requireExact(result.getWorkerSessionId(), null, "WORKER_SESSION");
        } else {
            requireExact(trimToNull(result.getWorkerId()), route.selectedWorkerId(), "WORKER");
            requireExact(
                    result.getWorkerProviderType(), route.expectedProviderType(), "PROVIDER");
            requireText(result.getWorkerTaskId(), "BUSINESS_TASK_CREATE_RESULT_WORKER_TASK_MISSING");
        }
        if (identity.contextId() == null) {
            requireText(result.getContextId(), "BUSINESS_TASK_CREATE_RESULT_CONTEXT_MISSING");
        } else {
            requireExact(result.getContextId(), identity.contextId(), "CONTEXT");
        }
        requireText(
                result.getTaskScopedToken(),
                "BUSINESS_TASK_CREATE_RESULT_TASK_TOKEN_MISSING");
        return reference;
    }

    private static void requireExact(
            @Nullable String actual,
            @Nullable String expected,
            String field) {
        if (!Objects.equals(actual, expected)) {
            throw conflict("BUSINESS_TASK_CREATE_RESULT_" + field + "_CONFLICT");
        }
    }

    private static String requireText(@Nullable String value, String safeCode) {
        if (value == null || value.isBlank()) {
            throw conflict(safeCode);
        }
        return value;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static IllegalStateException conflict(String safeCode) {
        return new IllegalStateException(safeCode);
    }

    sealed interface BusinessTaskCreateCommandResult permits Executed, RecordedReplay {
        BusinessTaskReference reference();
    }

    record Executed(
            BusinessTaskReference reference,
            CreatedBusinessAgentTaskDTO freshTask)
            implements BusinessTaskCreateCommandResult {

        Executed {
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(freshTask, "freshTask must not be null");
        }

        @Override
        public String toString() {
            return "Executed[reference=" + reference + ", freshTask=REDACTED]";
        }
    }

    record RecordedReplay(BusinessTaskReference reference)
            implements BusinessTaskCreateCommandResult {

        RecordedReplay {
            Objects.requireNonNull(reference, "reference must not be null");
        }
    }

    record BusinessTaskReference(String taskId) {
        private static final String PREFIX = "BUSINESS_TASK:";

        BusinessTaskReference {
            if (taskId == null || taskId.isBlank()) {
                throw conflict("BUSINESS_TASK_CREATE_RESULT_TASK_ID_MISSING");
            }
            if (taskId.chars().anyMatch(Character::isISOControl)) {
                throw conflict("BUSINESS_TASK_CREATE_RESULT_TASK_ID_INVALID");
            }
            if ((PREFIX + taskId).length() > 320) {
                throw conflict("BUSINESS_TASK_CREATE_RESULT_TASK_ID_TOO_LONG");
            }
        }

        String opaqueValue() {
            return PREFIX + taskId;
        }

        static BusinessTaskReference fromOpaque(@Nullable String opaqueValue) {
            if (opaqueValue == null || !opaqueValue.startsWith(PREFIX)) {
                throw conflict("BUSINESS_TASK_CREATE_RECORDED_REFERENCE_INVALID");
            }
            return new BusinessTaskReference(opaqueValue.substring(PREFIX.length()));
        }
    }

    /** Plan-derived envelope facts shared with the later server-owned composition facade. */
    static final class PlanBinding {
        private static final String TENANT_PREFIX = "navi.tenant.present.v1:";
        private static final String UPSTREAM_PREFIX = "BUSINESS_UPSTREAM_SHA256:";
        private static final String UPSTREAM_DIGEST_DOMAIN =
                "navi.business-task-create-upstream.v1";
        private static final String SCOPE_PREFIX =
                "BUSINESS_TASK_CREATE_RECEIPT_SCOPE_SHA256_V1:";

        private final CanonicalCommandEnvelope.Ownership ownership;
        private final CanonicalCommandEnvelope.Target target;
        private final CanonicalCommandEnvelope.Effect effect;

        private PlanBinding(
                CanonicalCommandEnvelope.Ownership ownership,
                CanonicalCommandEnvelope.Target target,
                CanonicalCommandEnvelope.Effect effect) {
            this.ownership = ownership;
            this.target = target;
            this.effect = effect;
        }

        static PlanBinding from(BusinessAgentTaskPreparedFreshCreate prepared) {
            Objects.requireNonNull(prepared, "prepared must not be null");
            BusinessAgentTaskCreatePlan plan = prepared.plan();
            BusinessAgentTaskCreatePlan.Identity identity = plan.identity();
            BusinessAgentTaskCreatePlan.AgentRoute route = plan.agentRoute();
            boolean launcherPresent = route.launcherType() != null;
            CanonicalCommandEnvelope.Ownership ownership =
                    new CanonicalCommandEnvelope.Ownership(
                            TENANT_PREFIX + identity.tenantId(),
                            identity.actorUserId(),
                            identity.clientAppId(),
                            UPSTREAM_PREFIX + digestUpstream(identity));
            CanonicalCommandEnvelope.Target target = new CanonicalCommandEnvelope.Target(
                    CanonicalCommandEnvelope.TargetKind.LOGICAL_AGENT,
                    route.agentId(),
                    route.agentId(),
                    launcherPresent ? route.expectedProviderType() : null,
                    launcherPresent ? route.selectedWorkerId() : null,
                    plan.modelTarget().modelConfigId(),
                    null,
                    identity.sessionId());
            CanonicalCommandEnvelope.Effect effect = new CanonicalCommandEnvelope.Effect(
                    BUSINESS_TASK_CREATE_ACTION,
                    SCOPE_PREFIX
                            + plan.receiptSemanticFingerprint(prepared.input().contextId()));
            return new PlanBinding(ownership, target, effect);
        }

        void requireEnvelope(CanonicalCommandEnvelope envelope) {
            CanonicalCommandEnvelope.CommandBinding binding = envelope.binding();
            if (binding.commandKind() != CanonicalCommandEnvelope.CommandKind.CREATE) {
                throw conflict("BUSINESS_TASK_CREATE_COMMAND_KIND_CONFLICT");
            }
            if (!ownership.equals(binding.ownership())) {
                throw conflict("BUSINESS_TASK_CREATE_OWNERSHIP_CONFLICT");
            }
            if (!target.equals(binding.target())) {
                throw conflict("BUSINESS_TASK_CREATE_TARGET_CONFLICT");
            }
            if (!effect.equals(binding.effect())) {
                throw conflict("BUSINESS_TASK_CREATE_EFFECT_BINDING_CONFLICT");
            }
        }

        CanonicalCommandEnvelope.Ownership ownership() {
            return ownership;
        }

        CanonicalCommandEnvelope.Target target() {
            return target;
        }

        CanonicalCommandEnvelope.Effect effect() {
            return effect;
        }

        @Override
        public String toString() {
            return "BusinessTaskCreatePlanBinding[content-free]";
        }

        private static String digestUpstream(BusinessAgentTaskCreatePlan.Identity identity) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                update(digest, UPSTREAM_DIGEST_DOMAIN);
                update(digest, identity.tenantId());
                update(digest, identity.clientAppId());
                update(digest, identity.upstreamSystemId());
                update(digest, identity.upstreamUserId());
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
        }

        private static void update(MessageDigest digest, @Nullable String value) {
            if (value == null) {
                digest.update((byte) 0);
                return;
            }
            digest.update((byte) 1);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) (bytes.length >>> 24));
            digest.update((byte) (bytes.length >>> 16));
            digest.update((byte) (bytes.length >>> 8));
            digest.update((byte) bytes.length);
            digest.update(bytes);
        }
    }
}
