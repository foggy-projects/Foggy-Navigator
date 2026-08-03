package com.foggy.navigator.session.service;

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

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Server-owned command factory and penultimate submit stage for trusted
 * Navigator MVC task creation.
 */
@Service
public final class TrustedNavigatorTaskCreateCommandFactory
        implements AgentSubmitPipelineStage {

    static final String TASK_ROUTE = "/api/v1/tasks";
    static final String AGENT_ASK_ROUTE = "/api/v1/agents/{agentId}/ask";
    static final String FORWARD_ROUTE = "/api/v1/session-relations/forward";
    static final String UI_SOURCE = "UI";
    static final String A2A_SOURCE = "A2A";
    static final String UI_FORWARD_SOURCE = "UI_FORWARD";
    static final String UI_SURFACE = "NAVIGATOR_UI";
    static final String A2A_SURFACE = "NAVIGATOR_A2A";
    static final String UI_FORWARD_SURFACE = "NAVIGATOR_UI_FORWARD";

    private static final Pattern STRICT_SEMANTIC_FINGERPRINT =
            Pattern.compile("[0-9a-f]{64}");
    private static final String FORWARD_IDEMPOTENCY_PREFIX = "UI_FORWARD_SHA256:";
    private static final TrustedNavigatorCommandIngressAuthority.IngressDescriptor TASK_INGRESS =
            TrustedNavigatorCommandIngressAuthority.IngressDescriptor.TASK_CREATE_DIRECT;
    private static final TrustedNavigatorCommandIngressAuthority.IngressDescriptor
            TRANSITIONAL_AGENT_ASK_INGRESS =
            TrustedNavigatorCommandIngressAuthority.IngressDescriptor.TRANSITIONAL_AGENT_ASK;
    private static final TrustedNavigatorCommandIngressAuthority.IngressDescriptor A2A_INGRESS =
            TrustedNavigatorCommandIngressAuthority.IngressDescriptor.A2A_TASK_CREATE;
    private static final TrustedNavigatorCommandIngressAuthority.IngressDescriptor FORWARD_INGRESS =
            TrustedNavigatorCommandIngressAuthority.IngressDescriptor.SESSION_FORWARD_CREATE;

    private final TaskDispatchFacade taskDispatchFacade;
    private final TaskCreateCommandCoordinator commandCoordinator;
    private final VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private final TrustedNavigatorCommandIngressAuthority ingressAuthority;
    private final Object forwardScopeIssuer = new Object();
    private final ThreadLocal<ActiveForwardScope> activeForwardScope = new ThreadLocal<>();

    public TrustedNavigatorTaskCreateCommandFactory(
            TaskDispatchFacade taskDispatchFacade,
            TaskCreateCommandCoordinator commandCoordinator,
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority,
            TrustedNavigatorCommandIngressAuthority ingressAuthority) {
        this.taskDispatchFacade = Objects.requireNonNull(
                taskDispatchFacade, "taskDispatchFacade must not be null");
        this.commandCoordinator = Objects.requireNonNull(
                commandCoordinator, "commandCoordinator must not be null");
        this.serverAuthority = Objects.requireNonNull(
                serverAuthority, "serverAuthority must not be null");
        this.ingressAuthority = Objects.requireNonNull(
                ingressAuthority, "ingressAuthority must not be null");
    }

    @Override
    public String name() {
        return "trusted-navigator-task-create-command";
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE - 1;
    }

    ForwardCommandScope mintForwardScope(
            @Nullable String suppliedClientRequestId,
            String semanticFingerprint) {
        return new ForwardCommandScope(
                forwardScopeIssuer,
                ingressAuthority.canonicalCreateClientRequestId(suppliedClientRequestId),
                semanticFingerprint);
    }

    void preauthorizeForwardScope(
            ForwardCommandScope scope,
            AgentTaskSubmitRequest expectedRequest) {
        ActiveForwardScope existing = activeForwardScope.get();
        if (existing != null) {
            existing.poison();
            if (scope != null) {
                scope.poisonRejectedCandidate();
            }
            throw conflict("FORWARD_TASK_CREATE_SCOPE_NESTED");
        }
        Objects.requireNonNull(scope, "forward command scope must not be null");
        Objects.requireNonNull(expectedRequest, "expected submit request must not be null");
        scope.requireIssuer(forwardScopeIssuer);
        scope.beginPreauthorization(expectedRequest);
        try {
            scope.requireClientRequest(expectedRequest.getClientRequestId());
            requireTrustedIngress(expectedRequest, true);
            scope.completePreauthorization(expectedRequest);
        } catch (RuntimeException | Error failure) {
            scope.rejectPreauthorization();
            throw failure;
        }
    }

    <T> T executeForwardScoped(
            ForwardCommandScope scope,
            AgentTaskSubmitRequest expectedRequest,
            ForwardFreshParticipants participants,
            Supplier<T> submission) {
        ActiveForwardScope existing = activeForwardScope.get();
        if (existing != null) {
            existing.poison();
            if (scope != null) {
                scope.poisonRejectedCandidate();
            }
            throw conflict("FORWARD_TASK_CREATE_SCOPE_NESTED");
        }
        Objects.requireNonNull(scope, "forward command scope must not be null");
        Objects.requireNonNull(expectedRequest, "expected submit request must not be null");
        Objects.requireNonNull(participants, "forward fresh participants must not be null");
        Objects.requireNonNull(submission, "forward scoped submission must not be null");
        scope.requireIssuer(forwardScopeIssuer);
        scope.claimPreauthorizedExecution(expectedRequest);
        ActiveForwardScope active = new ActiveForwardScope(
                forwardScopeIssuer, scope, expectedRequest, participants);
        activeForwardScope.set(active);

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
            if (activeForwardScope.get() != active) {
                cleanupFailure = conflict("FORWARD_TASK_CREATE_SCOPE_CLEANUP_CONFLICT");
            }
            activeForwardScope.remove();
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
        if (activeForwardScope.get() != null) {
            return true;
        }
        AgentResolveContext context = request == null ? null : request.getResolveContext();
        String source = context == null ? null : context.getRequestSource();
        if (!UI_SOURCE.equals(source) && !A2A_SOURCE.equals(source)) {
            return false;
        }
        if (UI_SOURCE.equals(source)
                && ingressAuthority.routingOnlyCurrentRequestMatches(
                "POST", AGENT_ASK_ROUTE)) {
            return false;
        }
        return ingressAuthority.routingOnlyHasCurrentCredentialCandidate();
    }

    @Override
    public AgentTaskSubmitResult handle(
            AgentTaskSubmitRequest request,
            AgentSubmitPipelineChain chain) {
        Objects.requireNonNull(request, "submit request must not be null");
        Objects.requireNonNull(chain, "pipeline chain must not be null");
        ActiveForwardScope active = activeForwardScope.get();
        try {
            if (active != null) {
                active.requireAdapter(forwardScopeIssuer);
                active.claim(request);
            }
            TrustedIngress ingress = requireTrustedIngress(request, active != null);
            if (ingress.deferredLegacy()) {
                return chain.proceed(request);
            }

            String clientRequestId;
            String idempotencyKey;
            if (active == null) {
                clientRequestId = ingressAuthority.canonicalCreateClientRequestId(
                        request.getClientRequestId());
                request.setClientRequestId(clientRequestId);
                idempotencyKey = clientRequestId;
            } else {
                ForwardCommandScope scope = active.scope();
                scope.requireClientRequest(request.getClientRequestId());
                clientRequestId = scope.clientRequestId;
                idempotencyKey = FORWARD_IDEMPOTENCY_PREFIX + scope.semanticFingerprint;
            }
            AgentResolveContext context = Objects.requireNonNull(
                    request.getResolveContext(), "resolve context must not be null");
            TaskDispatchRequest dispatchRequest =
                    taskDispatchFacade.toTaskDispatchRequest(request);
            TaskCreateTargetResolver.CreateExecutionPlan plan =
                    taskDispatchFacade.resolveCreateExecutionPlan(dispatchRequest, context);
            if (!ingress.verified().ownerUserId().equals(plan.ownerUserId())
                    || !Objects.equals(ingress.verified().tenantId(), plan.tenantId())) {
                throw rejected("TRUSTED_NAVIGATOR_PLAN_OWNER_CONFLICT");
            }

            TaskCreateCommandCoordinator.PlanBinding planBinding =
                    TaskCreateCommandCoordinator.PlanBinding.from(plan);
            CanonicalCommandEnvelope.CommandBinding binding =
                    new CanonicalCommandEnvelope.CommandBinding(
                            CanonicalCommandEnvelope.CommandKind.CREATE,
                            new CanonicalCommandEnvelope.Ingress(
                                    ingress.verified().commandIngress(),
                                    ingress.verified().clientSurface(),
                                    ingress.verified().routeId()),
                            new CanonicalCommandEnvelope.Request(
                                    clientRequestId,
                                    idempotencyKey,
                                    clientRequestId),
                            new CanonicalCommandEnvelope.Actor(
                                    CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                                    AuthorizationPrincipalType.NAVIGATOR_USER,
                                    ingress.verified().credentialLane(),
                                    ingress.verified().principalFingerprint(),
                                    null),
                            new CanonicalCommandEnvelope.Ownership(
                                    planBinding.tenantReference(),
                                    ingress.verified().ownerUserId(),
                                    null,
                                    null),
                            planBinding.target(),
                            planBinding.effect());
            VerifiedCommandAuthorizationDecision decision = serverAuthority.issue(binding);
            CanonicalCommandEnvelope envelope = new CanonicalCommandEnvelope(
                    CanonicalCommandEnvelope.SCHEMA_VERSION,
                    binding,
                    decision.metadata());

            TaskCreateCommandCoordinator.TaskCreateCommandResult result = active == null
                    ? commandCoordinator.execute(
                            dispatchRequest, context, plan, envelope, decision)
                    : commandCoordinator.execute(
                            dispatchRequest,
                            context,
                            plan,
                            envelope,
                            decision,
                            active.commandParticipants());
            DispatchTaskDTO dispatchTask;
            if (result instanceof TaskCreateCommandCoordinator.Executed executed) {
                if (active != null) {
                    active.requireFreshCompletion(executed.freshTask());
                }
                dispatchTask = executed.freshTask();
            } else if (result instanceof TaskCreateCommandCoordinator.RecordedReplay replay) {
                if (active != null) {
                    active.requireReplayWithoutParticipants();
                }
                dispatchTask = hydrateRecordedTask(replay.reference(), context, plan);
            } else {
                throw conflict("TASK_CREATE_COMMAND_RESULT_MISSING");
            }
            AgentTaskSubmitResult submitResult = AgentTaskSubmitResult.of(
                    taskDispatchFacade.toA2aTask(dispatchTask), dispatchTask);
            if (active != null) {
                active.markResultVerified();
            }
            return submitResult;
        } catch (RuntimeException | Error failure) {
            if (active != null) {
                active.poison();
            }
            throw failure;
        }
    }

    private DispatchTaskDTO hydrateRecordedTask(
            TaskCreateCommandCoordinator.TaskReference reference,
            AgentResolveContext context,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        DispatchTaskDTO task = taskDispatchFacade.getTask(reference.taskId(), context)
                .orElseThrow(() -> conflict("TASK_CREATE_RECORDED_TASK_UNAVAILABLE"));
        requireExactHydratedTask(task, reference, plan);
        return task;
    }

    private static void requireExactHydratedTask(
            DispatchTaskDTO task,
            TaskCreateCommandCoordinator.TaskReference reference,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
        if (!reference.taskId().equals(task.getTaskId())) {
            throw conflict("TASK_CREATE_RECORDED_TASK_ID_CONFLICT");
        }
        requireCompatible("PROVIDER", task.getProviderType(), plan.providerType());
        requireCompatible("AGENT", task.getAgentId(), plan.logicalAgentId());
        requireCompatible("WORKER", task.getWorkerId(), plan.physicalWorkerId());
        requireCompatible("MODEL_CONFIG", task.getModelConfigId(), plan.modelConfigId());
        requireCompatible("MODEL", task.getModel(), plan.model());
        requireCompatible("SESSION", task.getSessionId(), plan.sessionId());
        requireCompatible("DIRECTORY", task.getDirectoryId(), plan.directoryId());
    }

    private static void requireCompatible(
            String field,
            @Nullable String actual,
            @Nullable String expected) {
        if (!Objects.equals(actual, expected)) {
            throw conflict("TASK_CREATE_RECORDED_" + field + "_CONFLICT");
        }
    }

    private TrustedIngress requireTrustedIngress(
            AgentTaskSubmitRequest request,
            boolean forwardScoped) {
        AgentResolveContext context = request.getResolveContext();
        if (forwardScoped) {
            return new TrustedIngress(
                    ingressAuthority.require(
                            context,
                            List.of(FORWARD_INGRESS),
                            "TRUSTED_NAVIGATOR_FORWARD_ROUTE_SOURCE_CONFLICT"),
                    false);
        }
        TrustedNavigatorCommandIngressAuthority.VerifiedIngress verified =
                ingressAuthority.require(
                        context,
                        List.of(TASK_INGRESS, TRANSITIONAL_AGENT_ASK_INGRESS, A2A_INGRESS),
                        "TRUSTED_NAVIGATOR_ROUTE_SOURCE_CONFLICT");
        return new TrustedIngress(
                verified,
                verified.descriptor() == TRANSITIONAL_AGENT_ASK_INGRESS);
    }

    private static SecurityException rejected(String safeCode) {
        return new SecurityException(safeCode);
    }

    private static IllegalStateException conflict(String safeCode) {
        return new IllegalStateException(safeCode);
    }

    interface ForwardFreshParticipants {
        void prepareFreshTask();

        void completeFreshTask(DispatchTaskDTO freshTask);
    }

    static final class ForwardCommandScope {
        private final Object issuer;
        private final String clientRequestId;
        private final String semanticFingerprint;
        @Nullable
        private AgentTaskSubmitRequest preauthorizedRequest;
        private boolean preauthorizationAttempted;
        private boolean preauthorizationApproved;
        private boolean preauthorizationPoisoned;
        private boolean executionClaimed;

        private ForwardCommandScope(
                Object issuer,
                String clientRequestId,
                String semanticFingerprint) {
            this.issuer = Objects.requireNonNull(issuer, "scope issuer must not be null");
            this.clientRequestId = Objects.requireNonNull(
                    clientRequestId, "client request ID must not be null");
            if (semanticFingerprint == null
                    || !STRICT_SEMANTIC_FINGERPRINT.matcher(semanticFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "semanticFingerprint must be 64 lowercase SHA-256 hex characters");
            }
            this.semanticFingerprint = semanticFingerprint;
        }

        String clientRequestId() {
            return clientRequestId;
        }

        private void requireIssuer(Object expectedIssuer) {
            if (issuer != expectedIssuer) {
                throw conflict("FORWARD_TASK_CREATE_SCOPE_ISSUER_CONFLICT");
            }
        }

        private void requireClientRequest(@Nullable String actualClientRequestId) {
            if (!clientRequestId.equals(actualClientRequestId)) {
                throw conflict("FORWARD_TASK_CREATE_SCOPE_REQUEST_ID_CONFLICT");
            }
        }

        private synchronized void beginPreauthorization(
                AgentTaskSubmitRequest expectedRequest) {
            if (executionClaimed) {
                throw conflict("FORWARD_TASK_CREATE_SCOPE_ALREADY_USED");
            }
            if (preauthorizationPoisoned || preauthorizationAttempted) {
                preauthorizationPoisoned = true;
                throw conflict("FORWARD_TASK_CREATE_PREAUTH_ALREADY_ATTEMPTED");
            }
            preauthorizationAttempted = true;
            preauthorizedRequest = Objects.requireNonNull(
                    expectedRequest, "expected submit request must not be null");
        }

        private synchronized void completePreauthorization(
                AgentTaskSubmitRequest expectedRequest) {
            if (preauthorizationPoisoned
                    || !preauthorizationAttempted
                    || preauthorizedRequest != expectedRequest
                    || preauthorizationApproved) {
                preauthorizationPoisoned = true;
                throw conflict("FORWARD_TASK_CREATE_PREAUTH_COMPLETION_CONFLICT");
            }
            preauthorizationApproved = true;
        }

        private synchronized void rejectPreauthorization() {
            poison();
        }

        private synchronized void poison() {
            preauthorizationPoisoned = true;
            preauthorizationApproved = false;
        }

        private synchronized void poisonRejectedCandidate() {
            if (!executionClaimed) {
                poison();
            }
        }

        private synchronized void claimPreauthorizedExecution(
                AgentTaskSubmitRequest expectedRequest) {
            if (executionClaimed) {
                throw conflict("FORWARD_TASK_CREATE_SCOPE_ALREADY_USED");
            }
            if (preauthorizationPoisoned
                    || !preauthorizationAttempted
                    || !preauthorizationApproved
                    || preauthorizedRequest == null) {
                preauthorizationPoisoned = true;
                throw conflict("FORWARD_TASK_CREATE_PREAUTH_MISSING");
            }
            if (preauthorizedRequest != expectedRequest) {
                preauthorizationPoisoned = true;
                throw conflict("FORWARD_TASK_CREATE_PREAUTH_REQUEST_CONFLICT");
            }
            executionClaimed = true;
        }

        @Override
        public String toString() {
            return "ForwardCommandScope[content-free]";
        }
    }

    private static final class ActiveForwardScope {
        private final Object factoryIssuer;
        private final ForwardCommandScope scope;
        private final AgentTaskSubmitRequest expectedRequest;
        private final ForwardFreshParticipants participants;

        private boolean claimed;
        private boolean prepareInvoked;
        private boolean prepareCompleted;
        private boolean completionInvoked;
        private boolean completionCompleted;
        @Nullable
        private DispatchTaskDTO completedTask;
        private boolean resultVerified;
        private boolean poisoned;

        private ActiveForwardScope(
                Object factoryIssuer,
                ForwardCommandScope scope,
                AgentTaskSubmitRequest expectedRequest,
                ForwardFreshParticipants participants) {
            this.factoryIssuer = factoryIssuer;
            this.scope = scope;
            this.expectedRequest = expectedRequest;
            this.participants = participants;
        }

        private ForwardCommandScope scope() {
            return scope;
        }

        private synchronized void requireAdapter(Object actualIssuer) {
            if (poisoned || factoryIssuer != actualIssuer) {
                poisoned = true;
                throw conflict("FORWARD_TASK_CREATE_ADAPTER_CONFLICT");
            }
        }

        private synchronized void claim(AgentTaskSubmitRequest actualRequest) {
            if (poisoned || claimed || actualRequest != expectedRequest) {
                poisoned = true;
                throw conflict("FORWARD_TASK_CREATE_SCOPE_REQUEST_CONFLICT");
            }
            claimed = true;
        }

        private TaskCreateCommandCoordinator.TaskCreateParticipants commandParticipants() {
            return new TaskCreateCommandCoordinator.TaskCreateParticipants() {
                @Override
                public void prepareFreshTask() {
                    prepare();
                }

                @Override
                public void completeFreshTask(DispatchTaskDTO freshTask) {
                    complete(freshTask);
                }
            };
        }

        private void prepare() {
            synchronized (this) {
                if (poisoned || !claimed || prepareInvoked || completionInvoked) {
                    poisoned = true;
                    throw conflict("FORWARD_TASK_CREATE_PREPARATION_CONFLICT");
                }
                prepareInvoked = true;
            }
            try {
                participants.prepareFreshTask();
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
                    throw conflict("FORWARD_TASK_CREATE_COMPLETION_CONFLICT");
                }
                completionInvoked = true;
            }
            try {
                participants.completeFreshTask(freshTask);
                synchronized (this) {
                    completedTask = freshTask;
                    completionCompleted = true;
                }
            } catch (RuntimeException | Error failure) {
                poison();
                throw failure;
            }
        }

        private synchronized void requireFreshCompletion(DispatchTaskDTO freshTask) {
            if (poisoned
                    || !claimed
                    || !prepareInvoked
                    || !prepareCompleted
                    || !completionInvoked
                    || !completionCompleted
                    || completedTask != freshTask) {
                poisoned = true;
                throw conflict("FORWARD_TASK_CREATE_FRESH_PARTICIPANTS_INCOMPLETE");
            }
        }

        private synchronized void requireReplayWithoutParticipants() {
            if (poisoned || !claimed || prepareInvoked || completionInvoked) {
                poisoned = true;
                throw conflict("FORWARD_TASK_CREATE_REPLAY_PARTICIPANT_CONFLICT");
            }
        }

        private synchronized void markResultVerified() {
            if (poisoned || resultVerified) {
                poisoned = true;
                throw conflict("FORWARD_TASK_CREATE_RESULT_CONFLICT");
            }
            resultVerified = true;
        }

        private synchronized void requireSuccessfulExit() {
            if (poisoned || !claimed || !resultVerified) {
                throw conflict(poisoned
                        ? "FORWARD_TASK_CREATE_SCOPE_POISONED"
                        : "FORWARD_TASK_CREATE_SCOPE_NOT_CONSUMED");
            }
        }

        private synchronized void poison() {
            poisoned = true;
        }
    }

    private record TrustedIngress(
            TrustedNavigatorCommandIngressAuthority.VerifiedIngress verified,
            boolean deferredLegacy) {
        private TrustedIngress {
            Objects.requireNonNull(verified, "verified ingress must not be null");
        }
    }
}
