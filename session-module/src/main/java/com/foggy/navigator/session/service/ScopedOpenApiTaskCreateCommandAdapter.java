package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipelineChain;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipelineStage;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Process-local OpenAPI adapter for the canonical task-create command lane.
 *
 * <p>An authenticated HTTP adapter opens one non-nestable scope around its existing submit
 * pipeline call.  The scope carries only server-verified, content-free references and process-local
 * fresh-task callbacks.  An unscoped {@code OPEN_API} request deliberately remains on the legacy
 * lane until its owning caller is migrated.</p>
 */
@Service
public final class ScopedOpenApiTaskCreateCommandAdapter implements AgentSubmitPipelineStage {

    static final String OPEN_API_SOURCE = "OPEN_API";
    static final String OPEN_API_SURFACE = "NAVIGATOR_OPEN_API";
    static final String OPEN_API_ASK_ROUTE = "/api/v1/open/agents/{agentId}/ask";

    private static final String ACTOR_FINGERPRINT_DOMAIN =
            "navi.open-api-client-app-principal-fingerprint.v1";
    private static final String UPSTREAM_REFERENCE_DOMAIN =
            "navi.open-api-upstream-reference.v1";
    private static final ThreadLocal<ActiveScope> ACTIVE_SCOPE = new ThreadLocal<>();

    private final TaskDispatchFacade taskDispatchFacade;
    private final TaskCreateCommandCoordinator commandCoordinator;
    private final VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;

    public ScopedOpenApiTaskCreateCommandAdapter(
            TaskDispatchFacade taskDispatchFacade,
            TaskCreateCommandCoordinator commandCoordinator,
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority) {
        this.taskDispatchFacade = Objects.requireNonNull(
                taskDispatchFacade, "taskDispatchFacade must not be null");
        this.commandCoordinator = Objects.requireNonNull(
                commandCoordinator, "commandCoordinator must not be null");
        this.serverAuthority = Objects.requireNonNull(
                serverAuthority, "serverAuthority must not be null");
    }

    @Override
    public String name() {
        return "scoped-openapi-task-create-command";
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE - 2;
    }

    /**
     * Runs exactly one submit call in a non-inheritable, non-nestable OpenAPI authority scope.
     */
    public <T> T executeScoped(
            OpenApiCommandScope scope,
            AgentTaskSubmitRequest expectedRequest,
            FreshParticipants participants,
            Supplier<T> submission) {
        Objects.requireNonNull(scope, "OpenAPI command scope must not be null");
        Objects.requireNonNull(expectedRequest, "expected submit request must not be null");
        Objects.requireNonNull(participants, "fresh participants must not be null");
        Objects.requireNonNull(submission, "scoped submission must not be null");

        ActiveScope existing = ACTIVE_SCOPE.get();
        if (existing != null) {
            existing.poison();
            throw conflict("OPENAPI_TASK_CREATE_SCOPE_NESTED");
        }
        scope.claimExecution();
        ActiveScope active = new ActiveScope(scope, expectedRequest, participants);
        ACTIVE_SCOPE.set(active);

        Throwable primaryFailure = null;
        try {
            T result = submission.get();
            active.requireSuccessfulExit();
            return result;
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = null;
            if (ACTIVE_SCOPE.get() != active) {
                cleanupFailure = conflict("OPENAPI_TASK_CREATE_SCOPE_CLEANUP_CONFLICT");
            }
            ACTIVE_SCOPE.remove();
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    @Override
    public boolean supports(AgentTaskSubmitRequest request) {
        // While a scope is active this stage must intercept even a tampered source/request.
        return ACTIVE_SCOPE.get() != null;
    }

    @Override
    public AgentTaskSubmitResult handle(
            AgentTaskSubmitRequest request,
            AgentSubmitPipelineChain chain) {
        Objects.requireNonNull(chain, "submit pipeline chain must not be null");
        ActiveScope active = requireActiveScope();
        try {
            active.claim(request);
            OpenApiCommandScope scope = active.scope();
            AgentResolveContext context = requireScopedRequest(scope, request);
            TaskDispatchRequest dispatchRequest =
                    taskDispatchFacade.toTaskDispatchRequest(request);
            TaskCreateTargetResolver.CreateExecutionPlan plan =
                    taskDispatchFacade.resolveCreateExecutionPlan(dispatchRequest, context);
            scope.requirePlan(plan);

            TaskCreateCommandCoordinator.PlanBinding planBinding =
                    TaskCreateCommandCoordinator.PlanBinding.from(plan);
            CanonicalCommandEnvelope.CommandBinding binding =
                    new CanonicalCommandEnvelope.CommandBinding(
                            CanonicalCommandEnvelope.CommandKind.CREATE,
                            new CanonicalCommandEnvelope.Ingress(
                                    CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                                    OPEN_API_SURFACE,
                                    OPEN_API_ASK_ROUTE),
                            new CanonicalCommandEnvelope.Request(
                                    scope.clientRequestId,
                                    scope.clientRequestId,
                                    scope.clientRequestId),
                            new CanonicalCommandEnvelope.Actor(
                                    CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                                    AuthorizationPrincipalType.CLIENT_APP,
                                    AuthorizationCredentialLane.CLIENT_APP_RUNTIME_ACCESS,
                                    scope.actorFingerprint(),
                                    null),
                            new CanonicalCommandEnvelope.Ownership(
                                    planBinding.tenantReference(),
                                    plan.ownerUserId(),
                                    scope.clientAppId,
                                    scope.upstreamReference()),
                            planBinding.target(),
                            planBinding.effect());
            VerifiedCommandAuthorizationDecision decision = serverAuthority.issue(binding);
            CanonicalCommandEnvelope envelope = new CanonicalCommandEnvelope(
                    CanonicalCommandEnvelope.SCHEMA_VERSION,
                    binding,
                    decision.metadata());

            TaskCreateCommandCoordinator.TaskCreateCommandResult commandResult =
                    commandCoordinator.execute(
                            dispatchRequest,
                            context,
                            plan,
                            envelope,
                            decision,
                            active.commandParticipants(dispatchRequest));
            DispatchTaskDTO dispatchTask;
            if (commandResult instanceof TaskCreateCommandCoordinator.Executed executed) {
                active.requireFreshCompletion();
                dispatchTask = executed.freshTask();
            } else if (commandResult instanceof TaskCreateCommandCoordinator.RecordedReplay replay) {
                active.requireReplayWithoutParticipants();
                dispatchTask = hydrateRecordedTask(replay.reference(), context, plan);
            } else {
                throw conflict("OPENAPI_TASK_CREATE_COMMAND_RESULT_MISSING");
            }
            return AgentTaskSubmitResult.of(
                    taskDispatchFacade.toA2aTask(dispatchTask), dispatchTask);
        } catch (RuntimeException | Error failure) {
            active.poison();
            throw failure;
        }
    }

    private DispatchTaskDTO hydrateRecordedTask(
            TaskCreateCommandCoordinator.TaskReference reference,
            AgentResolveContext context,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        DispatchTaskDTO task = taskDispatchFacade.getTask(reference.taskId(), context)
                .orElseThrow(() -> conflict("OPENAPI_TASK_CREATE_RECORDED_TASK_UNAVAILABLE"));
        requireExactHydratedTask(task, reference, plan);
        return task;
    }

    private static void requireExactHydratedTask(
            DispatchTaskDTO task,
            TaskCreateCommandCoordinator.TaskReference reference,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        requireEqual("TASK_ID", reference.taskId(), task.getTaskId());
        requireEqual("PROVIDER", plan.providerType(), task.getProviderType());
        requireEqual("AGENT", plan.logicalAgentId(), task.getAgentId());
        requireEqual("WORKER", plan.physicalWorkerId(), task.getWorkerId());
        requireEqual("MODEL_CONFIG", plan.modelConfigId(), task.getModelConfigId());
        requireEqual("MODEL", plan.model(), task.getModel());
        requireEqual("SESSION", plan.sessionId(), task.getSessionId());
        requireEqual("DIRECTORY", plan.directoryId(), task.getDirectoryId());
    }

    private static AgentResolveContext requireScopedRequest(
            OpenApiCommandScope scope,
            AgentTaskSubmitRequest request) {
        if (request == null) {
            throw conflict("OPENAPI_TASK_CREATE_REQUEST_MISSING");
        }
        AgentResolveContext context = request.getResolveContext();
        if (context == null
                || !OPEN_API_SOURCE.equals(context.getRequestSource())
                || !scope.clientRequestId.equals(request.getClientRequestId())
                || !scope.tenantId.equals(context.getTenantId())
                || !scope.ownerUserId.equals(context.getUserId())) {
            throw conflict("OPENAPI_TASK_CREATE_SCOPE_AUTHORITY_CONFLICT");
        }
        scope.target.requireRequest(request);
        return context;
    }

    private static ActiveScope requireActiveScope() {
        ActiveScope active = ACTIVE_SCOPE.get();
        if (active == null) {
            throw conflict("OPENAPI_TASK_CREATE_SCOPE_MISSING");
        }
        return active;
    }

    private static void requireEqual(String field, @Nullable Object expected, @Nullable Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw conflict("OPENAPI_TASK_CREATE_RECORDED_" + field + "_CONFLICT");
        }
    }

    private static String digest(String domain, String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigestField(digest, domain);
            for (String field : fields) {
                updateDigestField(digest, field);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String digestNullable(String domain, @Nullable String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigestField(digest, domain);
            for (String field : fields) {
                digest.update(field == null ? (byte) 0 : (byte) 1);
                if (field != null) {
                    updateDigestField(digest, field);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static void updateDigestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String requireCanonicalRequestId(String value) {
        requireExactText(value, "clientRequestId");
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) {
                throw conflict("OPENAPI_TASK_CREATE_CLIENT_REQUEST_ID_INVALID");
            }
            return canonical;
        } catch (IllegalArgumentException invalid) {
            throw conflict("OPENAPI_TASK_CREATE_CLIENT_REQUEST_ID_INVALID");
        }
    }

    private static String requireExactText(String value, String field) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > CanonicalCommandEnvelope.MAX_REFERENCE_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    @Nullable
    private static String optionalExactText(@Nullable String value, String field) {
        return value == null ? null : requireExactText(value, field);
    }

    private static IllegalStateException conflict(String safeCode) {
        return new IllegalStateException(safeCode);
    }

    /** Process-local callbacks. Implementations must not retain the canonical request. */
    public interface FreshParticipants {
        void prepare(TaskDispatchRequest canonicalRequest);

        void complete(TaskDispatchRequest canonicalRequest, DispatchTaskDTO freshTask);
    }

    /** Safe target expectations established before the once-effect receipt is prepared. */
    public record TargetExpectation(
            String agentId,
            String contextId,
            @Nullable String providerType,
            @Nullable String physicalWorkerId,
            String modelConfigId,
            @Nullable String model,
            @Nullable String directoryId) {

        public TargetExpectation {
            agentId = requireExactText(agentId, "agentId");
            contextId = requireExactText(contextId, "contextId");
            providerType = optionalExactText(providerType, "providerType");
            physicalWorkerId = optionalExactText(physicalWorkerId, "physicalWorkerId");
            modelConfigId = requireExactText(modelConfigId, "modelConfigId");
            model = optionalExactText(model, "model");
            directoryId = optionalExactText(directoryId, "directoryId");
        }

        private void requireRequest(AgentTaskSubmitRequest request) {
            if (!agentId.equals(request.getAgentId())
                    || !contextId.equals(request.getContextId())
                    || !matchesIfPresent(providerType, request.getProviderType())
                    || !matchesIfPresent(physicalWorkerId, request.getWorkerId())
                    || !modelConfigId.equals(request.getModelConfigId())
                    || !matchesIfPresent(model, request.getModel())
                    || !matchesIfPresent(directoryId, request.getDirectoryId())) {
                throw conflict("OPENAPI_TASK_CREATE_SCOPE_TARGET_CONFLICT");
            }
        }

        private void requirePlan(TaskCreateTargetResolver.CreateExecutionPlan plan) {
            if (!agentId.equals(plan.logicalAgentId())
                    || !matchesIfPresent(providerType, plan.providerType())
                    || !matchesIfPresent(physicalWorkerId, plan.physicalWorkerId())
                    || !modelConfigId.equals(plan.modelConfigId())
                    || !matchesIfPresent(model, plan.model())
                    || !matchesIfPresent(directoryId, plan.directoryId())) {
                throw conflict("OPENAPI_TASK_CREATE_SCOPE_PLAN_CONFLICT");
            }
        }

        private static boolean matchesIfPresent(@Nullable String expected, @Nullable String actual) {
            return expected == null || expected.equals(actual);
        }
    }

    /**
     * Immutable safe authority binding. Runtime-access evidence is validated and intentionally
     * discarded by the factory so token rotation cannot alter the stable actor fingerprint.
     */
    public static final class OpenApiCommandScope {
        private final String clientRequestId;
        private final String tenantId;
        private final String ownerUserId;
        private final String clientAppId;
        @Nullable
        private final String upstreamSystemId;
        @Nullable
        private final String upstreamUserId;
        private final String credentialId;
        private final TargetExpectation target;

        private boolean executionClaimed;

        private OpenApiCommandScope(
                String clientRequestId,
                String tenantId,
                String ownerUserId,
                String clientAppId,
                @Nullable String upstreamSystemId,
                @Nullable String upstreamUserId,
                String credentialId,
                TargetExpectation target) {
            this.clientRequestId = requireCanonicalRequestId(clientRequestId);
            this.tenantId = requireExactText(tenantId, "tenantId");
            this.ownerUserId = requireExactText(ownerUserId, "ownerUserId");
            this.clientAppId = requireExactText(clientAppId, "clientAppId");
            this.upstreamSystemId = optionalExactText(upstreamSystemId, "upstreamSystemId");
            this.upstreamUserId = optionalExactText(upstreamUserId, "upstreamUserId");
            this.credentialId = requireExactText(credentialId, "credentialId");
            this.target = Objects.requireNonNull(target, "target expectation must not be null");
        }

        public static OpenApiCommandScope authenticated(
                String clientRequestId,
                String tenantId,
                String ownerUserId,
                String clientAppId,
                @Nullable String upstreamSystemId,
                @Nullable String upstreamUserId,
                String credentialId,
                String runtimeAccessAuthenticationEvidence,
                TargetExpectation target) {
            requireExactText(runtimeAccessAuthenticationEvidence,
                    "runtimeAccessAuthenticationEvidence");
            return new OpenApiCommandScope(
                    clientRequestId,
                    tenantId,
                    ownerUserId,
                    clientAppId,
                    upstreamSystemId,
                    upstreamUserId,
                    credentialId,
                    target);
        }

        private synchronized void claimExecution() {
            if (executionClaimed) {
                throw conflict("OPENAPI_TASK_CREATE_SCOPE_ALREADY_USED");
            }
            executionClaimed = true;
        }

        private void requirePlan(TaskCreateTargetResolver.CreateExecutionPlan plan) {
            if (plan == null
                    || !tenantId.equals(plan.tenantId())
                    || !ownerUserId.equals(plan.ownerUserId())) {
                throw conflict("OPENAPI_TASK_CREATE_SCOPE_PLAN_AUTHORITY_CONFLICT");
            }
            target.requirePlan(plan);
        }

        private String actorFingerprint() {
            return digest(ACTOR_FINGERPRINT_DOMAIN, tenantId, clientAppId, credentialId);
        }

        @Nullable
        private String upstreamReference() {
            return upstreamSystemId == null && upstreamUserId == null
                    ? null
                    : "OPENAPI_UPSTREAM_SHA256:"
                    + digestNullable(
                            UPSTREAM_REFERENCE_DOMAIN,
                            tenantId,
                            clientAppId,
                            upstreamSystemId,
                            upstreamUserId);
        }

        @Override
        public String toString() {
            return "OpenApiCommandScope[content-free]";
        }
    }

    private static final class ActiveScope {
        private final OpenApiCommandScope scope;
        private final AgentTaskSubmitRequest expectedRequest;
        private final FreshParticipants participants;

        private boolean claimed;
        private boolean prepareInvoked;
        private boolean prepareCompleted;
        private boolean completionInvoked;
        private boolean completionCompleted;
        private boolean poisoned;

        private ActiveScope(
                OpenApiCommandScope scope,
                AgentTaskSubmitRequest expectedRequest,
                FreshParticipants participants) {
            this.scope = scope;
            this.expectedRequest = expectedRequest;
            this.participants = participants;
        }

        private OpenApiCommandScope scope() {
            return scope;
        }

        private synchronized void claim(AgentTaskSubmitRequest actualRequest) {
            if (poisoned || claimed || actualRequest != expectedRequest) {
                poisoned = true;
                throw conflict("OPENAPI_TASK_CREATE_SCOPE_REQUEST_CONFLICT");
            }
            claimed = true;
        }

        private TaskCreateCommandCoordinator.TaskCreateParticipants commandParticipants(
                TaskDispatchRequest canonicalRequest) {
            return new TaskCreateCommandCoordinator.TaskCreateParticipants() {
                @Override
                public void prepareFreshTask() {
                    prepare(canonicalRequest);
                }

                @Override
                public void completeFreshTask(DispatchTaskDTO freshTask) {
                    complete(canonicalRequest, freshTask);
                }
            };
        }

        private void prepare(TaskDispatchRequest canonicalRequest) {
            synchronized (this) {
                if (poisoned || !claimed || prepareInvoked || completionInvoked) {
                    poisoned = true;
                    throw conflict("OPENAPI_TASK_CREATE_PREPARATION_CONFLICT");
                }
                prepareInvoked = true;
            }
            try {
                participants.prepare(canonicalRequest);
                synchronized (this) {
                    prepareCompleted = true;
                }
            } catch (RuntimeException | Error failure) {
                poison();
                throw failure;
            }
        }

        private void complete(
                TaskDispatchRequest canonicalRequest,
                DispatchTaskDTO freshTask) {
            Objects.requireNonNull(freshTask, "fresh task must not be null");
            synchronized (this) {
                if (poisoned || !prepareCompleted || completionInvoked) {
                    poisoned = true;
                    throw conflict("OPENAPI_TASK_CREATE_COMPLETION_CONFLICT");
                }
                completionInvoked = true;
            }
            try {
                participants.complete(canonicalRequest, freshTask);
                synchronized (this) {
                    completionCompleted = true;
                }
            } catch (RuntimeException | Error failure) {
                poison();
                throw failure;
            }
        }

        private synchronized void requireFreshCompletion() {
            if (poisoned
                    || !claimed
                    || !prepareInvoked
                    || !prepareCompleted
                    || !completionInvoked
                    || !completionCompleted) {
                poisoned = true;
                throw conflict("OPENAPI_TASK_CREATE_FRESH_PARTICIPANTS_INCOMPLETE");
            }
        }

        private synchronized void requireReplayWithoutParticipants() {
            if (poisoned || !claimed || prepareInvoked || completionInvoked) {
                poisoned = true;
                throw conflict("OPENAPI_TASK_CREATE_REPLAY_PARTICIPANT_CONFLICT");
            }
        }

        private synchronized void requireSuccessfulExit() {
            if (poisoned || !claimed) {
                throw conflict(poisoned
                        ? "OPENAPI_TASK_CREATE_SCOPE_POISONED"
                        : "OPENAPI_TASK_CREATE_SCOPE_NOT_CONSUMED");
            }
        }

        private synchronized void poison() {
            poisoned = true;
        }
    }
}
