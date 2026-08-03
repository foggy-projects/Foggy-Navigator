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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Process-local Sharing Key adapter for the canonical task-create command lane. */
@Service
public final class ScopedSharedTaskCreateCommandAdapter implements AgentSubmitPipelineStage {

    static final String SHARED_SOURCE = "SHARED_API";
    static final String SHARED_SURFACE = "NAVIGATOR_SHARED_API";
    static final String SHARED_ASK_ROUTE = "/api/v1/shared/ask";

    private static final String ACTOR_FINGERPRINT_DOMAIN =
            "navi.shared-agent-capability-fingerprint.v1";
    private static final ThreadLocal<ActiveScope> ACTIVE_SCOPE = new ThreadLocal<>();

    private final TaskDispatchFacade taskDispatchFacade;
    private final TaskCreateCommandCoordinator commandCoordinator;
    private final VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private final SharingKeyService sharingKeyService;
    private final Object scopeIssuer = new Object();

    public ScopedSharedTaskCreateCommandAdapter(
            TaskDispatchFacade taskDispatchFacade,
            TaskCreateCommandCoordinator commandCoordinator,
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority,
            SharingKeyService sharingKeyService) {
        this.taskDispatchFacade = Objects.requireNonNull(
                taskDispatchFacade, "taskDispatchFacade must not be null");
        this.commandCoordinator = Objects.requireNonNull(
                commandCoordinator, "commandCoordinator must not be null");
        this.serverAuthority = Objects.requireNonNull(
                serverAuthority, "serverAuthority must not be null");
        this.sharingKeyService = Objects.requireNonNull(
                sharingKeyService, "sharingKeyService must not be null");
    }

    @Override
    public String name() {
        return "scoped-shared-task-create-command";
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE - 3;
    }

    /** Performs read-only Sharing Key preflight and discards the raw credential. */
    public SharedCommandScope mintScope(
            String plainSharingKey,
            @Nullable String suppliedClientRequestId) {
        SharingKeyService.SharedAskAuthority authority =
                sharingKeyService.mintAskAuthority(plainSharingKey);
        return new SharedCommandScope(
                scopeIssuer,
                authority,
                canonicalClientRequestId(suppliedClientRequestId));
    }

    /** Runs exactly one submit call in a non-inheritable, non-nestable Shared authority scope. */
    public <T> T executeScoped(
            SharedCommandScope scope,
            AgentTaskSubmitRequest expectedRequest,
            FreshParticipants participants,
            Supplier<T> submission) {
        ActiveScope existing = ACTIVE_SCOPE.get();
        if (existing != null) {
            existing.poison();
            throw conflict("SHARED_TASK_CREATE_SCOPE_NESTED");
        }
        Objects.requireNonNull(scope, "Shared command scope must not be null");
        Objects.requireNonNull(expectedRequest, "expected submit request must not be null");
        Objects.requireNonNull(participants, "fresh participants must not be null");
        Objects.requireNonNull(submission, "scoped submission must not be null");
        scope.requireIssuer(scopeIssuer);
        scope.claimExecution();
        ActiveScope active = new ActiveScope(
                scopeIssuer, scope, expectedRequest, participants, sharingKeyService);
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
                cleanupFailure = conflict("SHARED_TASK_CREATE_SCOPE_CLEANUP_CONFLICT");
            }
            ACTIVE_SCOPE.remove();
            if (cleanupFailure != null) {
                if (primaryFailure == null) {
                    throw cleanupFailure;
                }
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    @Override
    public boolean supports(AgentTaskSubmitRequest request) {
        return ACTIVE_SCOPE.get() != null;
    }

    @Override
    public AgentTaskSubmitResult handle(
            AgentTaskSubmitRequest request,
            AgentSubmitPipelineChain chain) {
        Objects.requireNonNull(chain, "submit pipeline chain must not be null");
        ActiveScope active = requireActiveScope();
        try {
            active.requireAdapter(scopeIssuer);
            active.claim(request);
            SharedCommandScope scope = active.scope();
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
                                    CanonicalCommandEnvelope.CommandIngress.SHARED,
                                    SHARED_SURFACE,
                                    SHARED_ASK_ROUTE),
                            new CanonicalCommandEnvelope.Request(
                                    scope.clientRequestId,
                                    scope.clientRequestId,
                                    scope.clientRequestId),
                            new CanonicalCommandEnvelope.Actor(
                                    CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                                    AuthorizationPrincipalType.SHARE_GRANTEE,
                                    AuthorizationCredentialLane.SHARING_KEY_CAPABILITY,
                                    scope.actorFingerprint(),
                                    null),
                            new CanonicalCommandEnvelope.Ownership(
                                    planBinding.tenantReference(),
                                    scope.ownerUserId,
                                    null,
                                    null),
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
                throw conflict("SHARED_TASK_CREATE_COMMAND_RESULT_MISSING");
            }
            AgentTaskSubmitResult result = AgentTaskSubmitResult.of(
                    taskDispatchFacade.toA2aTask(dispatchTask), dispatchTask);
            active.markResultVerified();
            return result;
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
                .orElseThrow(() -> conflict("SHARED_TASK_CREATE_RECORDED_TASK_UNAVAILABLE"));
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
            SharedCommandScope scope,
            AgentTaskSubmitRequest request) {
        if (request == null) {
            throw conflict("SHARED_TASK_CREATE_REQUEST_MISSING");
        }
        AgentResolveContext context = request.getResolveContext();
        if (context == null
                || !SHARED_SOURCE.equals(context.getRequestSource())
                || !scope.clientRequestId.equals(request.getClientRequestId())
                || !scope.ownerUserId.equals(context.getUserId())
                || !scope.tenantId.equals(context.getTenantId())
                || !scope.agentId.equals(request.getAgentId())) {
            throw conflict("SHARED_TASK_CREATE_SCOPE_AUTHORITY_CONFLICT");
        }
        return context;
    }

    private static ActiveScope requireActiveScope() {
        ActiveScope active = ACTIVE_SCOPE.get();
        if (active == null) {
            throw conflict("SHARED_TASK_CREATE_SCOPE_MISSING");
        }
        return active;
    }

    private static void requireEqual(
            String field,
            @Nullable Object expected,
            @Nullable Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw conflict("SHARED_TASK_CREATE_RECORDED_" + field + "_CONFLICT");
        }
    }

    private static LockedPolicyProjection projectLockedPolicy(
            TaskDispatchRequest canonicalRequest,
            SharingKeyService.SharedAskPolicySnapshot policy) {
        Objects.requireNonNull(canonicalRequest, "canonical request must not be null");
        Objects.requireNonNull(policy, "locked policy must not be null");
        Map<String, Object> metadata = canonicalRequest.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(canonicalRequest.getMetadata());
        Object suppliedSystemPrompt = metadata.get("systemPrompt");
        boolean hasExplicitSystemPrompt = suppliedSystemPrompt instanceof String text
                && !text.isBlank();
        if (!hasExplicitSystemPrompt) {
            String defaultSystemPrompt = policy.systemPrompt();
            if (defaultSystemPrompt == null || defaultSystemPrompt.isBlank()) {
                metadata.remove("systemPrompt");
            } else {
                metadata.put("systemPrompt", defaultSystemPrompt);
            }
        }
        if (policy.maxTurns() == null) {
            metadata.remove("maxTurns");
        } else {
            metadata.put("maxTurns", policy.maxTurns());
        }
        canonicalRequest.setMaxTurns(policy.maxTurns());
        canonicalRequest.setMetadata(metadata.isEmpty() ? null : metadata);
        return LockedPolicyProjection.capture(canonicalRequest);
    }

    private record LockedPolicyProjection(
            @Nullable Integer maxTurns,
            @Nullable String systemPrompt) {

        private static LockedPolicyProjection capture(TaskDispatchRequest request) {
            Map<String, Object> metadata = request.getMetadata();
            Object projectedPrompt = metadata == null ? null : metadata.get("systemPrompt");
            if (projectedPrompt != null && !(projectedPrompt instanceof String)) {
                throw conflict("SHARED_TASK_CREATE_LOCKED_POLICY_CONFLICT");
            }
            return new LockedPolicyProjection(
                    request.getMaxTurns(), (String) projectedPrompt);
        }

        private void requireUnchanged(TaskDispatchRequest request) {
            Map<String, Object> metadata = request.getMetadata();
            boolean maxTurnsMatches = Objects.equals(maxTurns, request.getMaxTurns())
                    && (maxTurns == null
                            ? metadata == null || !metadata.containsKey("maxTurns")
                            : metadata != null
                                    && Objects.equals(maxTurns, metadata.get("maxTurns")));
            boolean systemPromptMatches = systemPrompt == null
                    ? metadata == null || !metadata.containsKey("systemPrompt")
                    : metadata != null
                            && Objects.equals(systemPrompt, metadata.get("systemPrompt"));
            if (!maxTurnsMatches || !systemPromptMatches) {
                throw conflict("SHARED_TASK_CREATE_LOCKED_POLICY_CONFLICT");
            }
        }
    }

    private static String canonicalClientRequestId(@Nullable String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            String canonical = UUID.fromString(supplied).toString();
            if (!canonical.equals(supplied)) {
                throw conflict("SHARED_TASK_CREATE_CLIENT_REQUEST_ID_INVALID");
            }
            return canonical;
        } catch (IllegalArgumentException invalid) {
            throw conflict("SHARED_TASK_CREATE_CLIENT_REQUEST_ID_INVALID");
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

    private static void updateDigestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
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

    private static IllegalStateException conflict(String safeCode) {
        return new IllegalStateException(safeCode);
    }

    /** Server callbacks that may run only on one fresh, permitted command attempt. */
    public interface FreshParticipants {
        void prepareFreshTask();

        void completeFreshTask(DispatchTaskDTO freshTask);
    }

    /** Typed business rejection emitted only by the locked Sharing Key admission step. */
    public static final class SharedCommandAdmissionRejectedException
            extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private SharedCommandAdmissionRejectedException(IllegalArgumentException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** One process-local, immutable authority scope with safe Controller preflight accessors. */
    public static final class SharedCommandScope {
        private final Object issuer;
        private final SharingKeyService.SharedAskAuthority authority;
        private final String sharingKeyId;
        private final String ownerUserId;
        private final String tenantId;
        private final String agentId;
        private final String clientRequestId;
        private boolean executionClaimed;

        private SharedCommandScope(
                Object issuer,
                SharingKeyService.SharedAskAuthority authority,
                String clientRequestId) {
            this.issuer = Objects.requireNonNull(issuer, "scope issuer must not be null");
            this.authority = Objects.requireNonNull(authority, "authority must not be null");
            this.sharingKeyId = requireExactText(
                    authority.sharingKeyId(), "sharingKeyId");
            this.ownerUserId = requireExactText(authority.ownerUserId(), "ownerUserId");
            this.tenantId = requireExactText(authority.tenantId(), "tenantId");
            this.agentId = requireExactText(authority.agentId(), "agentId");
            this.clientRequestId = requireExactText(clientRequestId, "clientRequestId");
        }

        public String sharingKeyId() { return sharingKeyId; }

        public String ownerUserId() { return ownerUserId; }

        public String tenantId() { return tenantId; }

        public String agentId() { return agentId; }

        public String clientRequestId() { return clientRequestId; }

        public AgentResolveContext newResolveContext() {
            return AgentResolveContext.builder()
                    .userId(ownerUserId)
                    .tenantId(tenantId)
                    .requestSource(SHARED_SOURCE)
                    .build();
        }

        private void requireIssuer(Object expectedIssuer) {
            if (issuer != expectedIssuer) {
                throw conflict("SHARED_TASK_CREATE_SCOPE_ISSUER_CONFLICT");
            }
        }

        private synchronized void claimExecution() {
            if (executionClaimed) {
                throw conflict("SHARED_TASK_CREATE_SCOPE_ALREADY_USED");
            }
            executionClaimed = true;
        }

        private void requirePlan(TaskCreateTargetResolver.CreateExecutionPlan plan) {
            if (plan == null
                    || !ownerUserId.equals(plan.ownerUserId())
                    || !tenantId.equals(plan.tenantId())
                    || !agentId.equals(plan.logicalAgentId())) {
                throw conflict("SHARED_TASK_CREATE_SCOPE_PLAN_AUTHORITY_CONFLICT");
            }
        }

        private String actorFingerprint() {
            return digest(
                    ACTOR_FINGERPRINT_DOMAIN,
                    tenantId,
                    ownerUserId,
                    sharingKeyId);
        }

        @Override
        public String toString() {
            return "SharedCommandScope[content-redacted]";
        }
    }

    private static final class ActiveScope {
        private final Object adapterIssuer;
        private final SharedCommandScope scope;
        private final AgentTaskSubmitRequest expectedRequest;
        private final FreshParticipants participants;
        private final SharingKeyService sharingKeyService;

        private boolean claimed;
        private boolean policyInvoked;
        private boolean policyCompleted;
        private LockedPolicyProjection lockedPolicyProjection;
        private boolean prepareInvoked;
        private boolean prepareCompleted;
        private boolean completionInvoked;
        private boolean completionCompleted;
        private boolean resultVerified;
        private boolean poisoned;

        private ActiveScope(
                Object adapterIssuer,
                SharedCommandScope scope,
                AgentTaskSubmitRequest expectedRequest,
                FreshParticipants participants,
                SharingKeyService sharingKeyService) {
            this.adapterIssuer = adapterIssuer;
            this.scope = scope;
            this.expectedRequest = expectedRequest;
            this.participants = participants;
            this.sharingKeyService = sharingKeyService;
        }

        private SharedCommandScope scope() { return scope; }

        private synchronized void requireAdapter(Object actualIssuer) {
            if (poisoned || adapterIssuer != actualIssuer) {
                poisoned = true;
                throw conflict("SHARED_TASK_CREATE_ADAPTER_CONFLICT");
            }
        }

        private synchronized void claim(AgentTaskSubmitRequest actualRequest) {
            if (poisoned || claimed || actualRequest != expectedRequest) {
                poisoned = true;
                throw conflict("SHARED_TASK_CREATE_SCOPE_REQUEST_CONFLICT");
            }
            claimed = true;
        }

        private TaskCreateCommandCoordinator.TaskCreateParticipants commandParticipants(
                TaskDispatchRequest canonicalRequest) {
            return new TaskCreateCommandCoordinator.TaskCreateParticipants() {
                @Override
                public void afterEffectPermitBeforeRoutePreparation() {
                    applyLockedPolicy(canonicalRequest);
                }

                @Override
                public void prepareFreshTask() {
                    prepare(canonicalRequest);
                }

                @Override
                public void completeFreshTask(DispatchTaskDTO freshTask) {
                    complete(freshTask);
                }
            };
        }

        private void applyLockedPolicy(TaskDispatchRequest canonicalRequest) {
            synchronized (this) {
                if (poisoned || !claimed || policyInvoked || prepareInvoked) {
                    poisoned = true;
                    throw conflict("SHARED_TASK_CREATE_POLICY_CONFLICT");
                }
                policyInvoked = true;
            }
            SharingKeyService.SharedAskPolicySnapshot policy;
            try {
                policy = sharingKeyService.consumeAuthorizedAsk(scope.authority);
            } catch (IllegalArgumentException rejection) {
                poison();
                throw new SharedCommandAdmissionRejectedException(rejection);
            } catch (RuntimeException | Error failure) {
                poison();
                throw failure;
            }
            try {
                LockedPolicyProjection projection =
                        projectLockedPolicy(canonicalRequest, policy);
                synchronized (this) {
                    lockedPolicyProjection = projection;
                    policyCompleted = true;
                }
            } catch (RuntimeException | Error failure) {
                poison();
                throw failure;
            }
        }

        private void prepare(TaskDispatchRequest canonicalRequest) {
            LockedPolicyProjection expectedPolicy;
            synchronized (this) {
                if (poisoned || !policyCompleted || prepareInvoked || completionInvoked) {
                    poisoned = true;
                    throw conflict("SHARED_TASK_CREATE_PREPARATION_CONFLICT");
                }
                prepareInvoked = true;
                expectedPolicy = Objects.requireNonNull(
                        lockedPolicyProjection,
                        "locked policy projection must not be null");
            }
            try {
                participants.prepareFreshTask();
                expectedPolicy.requireUnchanged(canonicalRequest);
                synchronized (this) {
                    prepareCompleted = true;
                }
            } catch (RuntimeException | Error failure) {
                poison();
                throw failure;
            }
        }

        private void complete(DispatchTaskDTO freshTask) {
            Objects.requireNonNull(freshTask, "fresh task must not be null");
            synchronized (this) {
                if (poisoned || !prepareCompleted || completionInvoked) {
                    poisoned = true;
                    throw conflict("SHARED_TASK_CREATE_COMPLETION_CONFLICT");
                }
                completionInvoked = true;
            }
            try {
                participants.completeFreshTask(freshTask);
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
                    || !policyCompleted
                    || !prepareCompleted
                    || !completionCompleted) {
                poisoned = true;
                throw conflict("SHARED_TASK_CREATE_FRESH_PARTICIPANTS_INCOMPLETE");
            }
        }

        private synchronized void requireReplayWithoutParticipants() {
            if (poisoned || !claimed || policyInvoked || prepareInvoked || completionInvoked) {
                poisoned = true;
                throw conflict("SHARED_TASK_CREATE_REPLAY_PARTICIPANT_CONFLICT");
            }
        }

        private synchronized void markResultVerified() {
            if (poisoned || resultVerified) {
                poisoned = true;
                throw conflict("SHARED_TASK_CREATE_RESULT_CONFLICT");
            }
            resultVerified = true;
        }

        private synchronized void requireSuccessfulExit() {
            if (poisoned || !claimed || !resultVerified) {
                throw conflict(poisoned
                        ? "SHARED_TASK_CREATE_SCOPE_POISONED"
                        : "SHARED_TASK_CREATE_SCOPE_NOT_CONSUMED");
            }
        }

        private synchronized void poison() {
            poisoned = true;
        }
    }
}
