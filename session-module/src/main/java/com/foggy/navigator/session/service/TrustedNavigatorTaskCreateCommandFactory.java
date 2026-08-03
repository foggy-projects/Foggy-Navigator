package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipelineChain;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipelineStage;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String QUERY_TOKEN = "token";
    private static final String API_KEY = "X-API-Key";
    private static final String JWT_FINGERPRINT_DOMAIN =
            "navi.navigator-jwt-principal-fingerprint.v1";
    private static final String API_KEY_FINGERPRINT_DOMAIN =
            "navi.navigator-api-key-principal-fingerprint.v1";
    private static final Pattern STRICT_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern STRICT_SEMANTIC_FINGERPRINT =
            Pattern.compile("[0-9a-f]{64}");
    private static final String FORWARD_IDEMPOTENCY_PREFIX = "UI_FORWARD_SHA256:";
    private static final List<String> FOREIGN_CREDENTIAL_HEADERS = List.of(
            "X-Navigator-API-Key",
            "X-Sharing-Key",
            "X-Navi-Principal-Credential",
            "X-Navi-Admin-Key",
            "X-Navi-Admin-Api-Key",
            "X-Navi-Operator-Key",
            "X-Navi-Operator-Api-Key",
            "X-Client-App-Control-Key",
            "X-Client-App-Key",
            "X-Client-App-Secret",
            "X-Client-App-Access-Token",
            "X-App-Key",
            "X-App-Secret",
            "X-App-Access-Token",
            "X-Foggy-App-Key",
            "X-Foggy-App-Secret",
            "X-Foggy-App-Access-Token",
            "X-Task-Token",
            "X-Task-Scoped-Token",
            "X-Worker-Token",
            "X-Navigator-Worker-Id",
            "X-Navigator-Worker-Credential",
            "X-Navigator-Worker-Lease-Id",
            "X-Worker-Id",
            "X-Platform-Admin-Key",
            "X-System-Admin-Key",
            "X-Operator-Token",
            "X-Principal-Token",
            "X-TMS-Agent-Token");

    private final TaskDispatchFacade taskDispatchFacade;
    private final TaskCreateCommandCoordinator commandCoordinator;
    private final VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private final Object forwardScopeIssuer = new Object();
    private final ThreadLocal<ActiveForwardScope> activeForwardScope = new ThreadLocal<>();

    public TrustedNavigatorTaskCreateCommandFactory(
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
                canonicalClientRequestId(suppliedClientRequestId),
                semanticFingerprint);
    }

    <T> T executeForwardScoped(
            ForwardCommandScope scope,
            AgentTaskSubmitRequest expectedRequest,
            ForwardFreshParticipants participants,
            Supplier<T> submission) {
        ActiveForwardScope existing = activeForwardScope.get();
        if (existing != null) {
            existing.poison();
            throw conflict("FORWARD_TASK_CREATE_SCOPE_NESTED");
        }
        Objects.requireNonNull(scope, "forward command scope must not be null");
        Objects.requireNonNull(expectedRequest, "expected submit request must not be null");
        Objects.requireNonNull(participants, "forward fresh participants must not be null");
        Objects.requireNonNull(submission, "forward scoped submission must not be null");
        scope.requireIssuer(forwardScopeIssuer);
        scope.claimExecution();
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
        HttpServletRequest servletRequest = currentServletRequest();
        if (servletRequest != null
                && UI_SOURCE.equals(source)
                && "POST".equals(servletRequest.getMethod())
                && AGENT_ASK_ROUTE.equals(servletRequest.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))) {
            return false;
        }
        return servletRequest != null && hasNavigatorCredentialCandidate(servletRequest);
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
            TrustedIngress ingress = requireTrustedIngress(request, active);
            if (ingress.deferredLegacy()) {
                return chain.proceed(request);
            }

            String clientRequestId;
            String idempotencyKey;
            if (active == null) {
                clientRequestId = canonicalClientRequestId(request.getClientRequestId());
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
            if (!ingress.ownerUserId().equals(plan.ownerUserId())
                    || !Objects.equals(ingress.tenantId(), plan.tenantId())) {
                throw rejected("TRUSTED_NAVIGATOR_PLAN_OWNER_CONFLICT");
            }

            TaskCreateCommandCoordinator.PlanBinding planBinding =
                    TaskCreateCommandCoordinator.PlanBinding.from(plan);
            CanonicalCommandEnvelope.CommandBinding binding =
                    new CanonicalCommandEnvelope.CommandBinding(
                            CanonicalCommandEnvelope.CommandKind.CREATE,
                            new CanonicalCommandEnvelope.Ingress(
                                    ingress.commandIngress(),
                                    ingress.clientSurface(),
                                    ingress.routeId()),
                            new CanonicalCommandEnvelope.Request(
                                    clientRequestId,
                                    idempotencyKey,
                                    clientRequestId),
                            new CanonicalCommandEnvelope.Actor(
                                    CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                                    AuthorizationPrincipalType.NAVIGATOR_USER,
                                    ingress.credentialSource().lane,
                                    principalFingerprint(
                                            ingress.ownerUserId(),
                                            ingress.credentialSource().fingerprintDomain),
                                    null),
                            new CanonicalCommandEnvelope.Ownership(
                                    planBinding.tenantReference(),
                                    ingress.ownerUserId(),
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
            @Nullable ActiveForwardScope active) {
        HttpServletRequest servletRequest = Objects.requireNonNull(
                currentServletRequest(), "trusted Navigator MVC request is unavailable");
        NavigatorCredentialSource credentialSource =
                requireNavigatorCredentialSource(servletRequest);
        rejectForeignCredentials(servletRequest, credentialSource);
        CurrentUser currentUser = UserContext.getCurrentUser();
        if (currentUser == null || isBlank(currentUser.getUserId())) {
            throw rejected("TRUSTED_NAVIGATOR_CURRENT_USER_MISSING");
        }
        requireAttribute(servletRequest, "userId", currentUser.getUserId());
        requireAttribute(servletRequest, "username", currentUser.getUsername());
        requireAttribute(servletRequest, "tenantId", currentUser.getTenantId());
        requireAttribute(servletRequest, "roles", currentUser.getRoles());

        AgentResolveContext context = request.getResolveContext();
        if (context == null
                || !Objects.equals(currentUser.getUserId(), context.getUserId())
                || !Objects.equals(currentUser.getTenantId(), context.getTenantId())) {
            throw rejected("TRUSTED_NAVIGATOR_RESOLVE_CONTEXT_CONFLICT");
        }
        if (!"POST".equals(servletRequest.getMethod())) {
            throw rejected("TRUSTED_NAVIGATOR_HTTP_METHOD_CONFLICT");
        }
        Object routeAttribute = servletRequest.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = routeAttribute == null ? null : routeAttribute.toString();
        String source = context.getRequestSource();
        if (active != null) {
            if (FORWARD_ROUTE.equals(route) && UI_FORWARD_SOURCE.equals(source)) {
                return new TrustedIngress(
                        CanonicalCommandEnvelope.CommandIngress.DIRECT,
                        UI_FORWARD_SURFACE,
                        FORWARD_ROUTE,
                        currentUser.getUserId(),
                        currentUser.getTenantId(),
                        credentialSource,
                        false);
            }
            throw rejected("TRUSTED_NAVIGATOR_FORWARD_ROUTE_SOURCE_CONFLICT");
        }
        if (TASK_ROUTE.equals(route) && UI_SOURCE.equals(source)) {
            return new TrustedIngress(
                    CanonicalCommandEnvelope.CommandIngress.DIRECT,
                    UI_SURFACE,
                    TASK_ROUTE,
                    currentUser.getUserId(),
                    currentUser.getTenantId(),
                    credentialSource,
                    false);
        }
        if (AGENT_ASK_ROUTE.equals(route) && UI_SOURCE.equals(source)) {
            return new TrustedIngress(
                    CanonicalCommandEnvelope.CommandIngress.A2A,
                    A2A_SURFACE,
                    AGENT_ASK_ROUTE,
                    currentUser.getUserId(),
                    currentUser.getTenantId(),
                    credentialSource,
                    true);
        }
        if (AGENT_ASK_ROUTE.equals(route) && A2A_SOURCE.equals(source)) {
            return new TrustedIngress(
                    CanonicalCommandEnvelope.CommandIngress.A2A,
                    A2A_SURFACE,
                    AGENT_ASK_ROUTE,
                    currentUser.getUserId(),
                    currentUser.getTenantId(),
                    credentialSource,
                    false);
        }
        throw rejected("TRUSTED_NAVIGATOR_ROUTE_SOURCE_CONFLICT");
    }

    private static NavigatorCredentialSource requireNavigatorCredentialSource(
            HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION);
        String queryToken = request.getParameter(QUERY_TOKEN);
        String apiKey = request.getHeader(API_KEY);
        boolean bearerCandidate = authorization != null
                && authorization.startsWith(BEARER_PREFIX);
        boolean queryCandidate = queryToken != null && !queryToken.isEmpty();
        boolean apiKeyCandidate = apiKey != null;
        int candidates = (bearerCandidate ? 1 : 0)
                + (queryCandidate ? 1 : 0)
                + (apiKeyCandidate ? 1 : 0);
        if (candidates > 1) {
            throw rejected("TRUSTED_NAVIGATOR_MIXED_AUTHORIZATION");
        }
        if (bearerCandidate) {
            if (isBlank(authorization.substring(BEARER_PREFIX.length()))) {
                throw rejected("TRUSTED_NAVIGATOR_BEARER_MISSING");
            }
            return NavigatorCredentialSource.BEARER;
        }
        if (queryCandidate) {
            if (isBlank(queryToken)) {
                throw rejected("TRUSTED_NAVIGATOR_QUERY_TOKEN_MISSING");
            }
            return NavigatorCredentialSource.QUERY_TOKEN;
        }
        if (apiKeyCandidate) {
            if (isBlank(apiKey)) {
                throw rejected("TRUSTED_NAVIGATOR_API_KEY_MISSING");
            }
            return NavigatorCredentialSource.API_KEY;
        }
        throw rejected("TRUSTED_NAVIGATOR_CREDENTIAL_SOURCE_MISSING");
    }

    private static void rejectForeignCredentials(
            HttpServletRequest request,
            NavigatorCredentialSource credentialSource) {
        String authorization = request.getHeader(AUTHORIZATION);
        String queryToken = request.getParameter(QUERY_TOKEN);
        String apiKey = request.getHeader(API_KEY);
        if ((credentialSource != NavigatorCredentialSource.BEARER
                && !isBlank(authorization))
                || (credentialSource != NavigatorCredentialSource.QUERY_TOKEN
                && !isBlank(queryToken))
                || (credentialSource != NavigatorCredentialSource.API_KEY
                && !isBlank(apiKey))) {
            throw rejected("TRUSTED_NAVIGATOR_MIXED_AUTHORIZATION");
        }
        for (String header : FOREIGN_CREDENTIAL_HEADERS) {
            if (!isBlank(request.getHeader(header))) {
                throw rejected("TRUSTED_NAVIGATOR_MIXED_CREDENTIAL_LANE");
            }
        }
    }

    private static void requireAttribute(
            HttpServletRequest request,
            String name,
            @Nullable String expected) {
        if (!Objects.equals(expected, request.getAttribute(name))) {
            throw rejected("TRUSTED_NAVIGATOR_AUTH_ATTRIBUTE_CONFLICT");
        }
    }

    private static boolean hasNavigatorCredentialCandidate(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return true;
        }
        String queryToken = request.getParameter(QUERY_TOKEN);
        if (queryToken != null && !queryToken.isEmpty()) {
            return true;
        }
        return request.getHeader(API_KEY) != null;
    }

    @Nullable
    private static HttpServletRequest currentServletRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest() : null;
    }

    private static String canonicalClientRequestId(@Nullable String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = supplied.trim();
        if (!STRICT_UUID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("clientRequestId must be a canonical UUID");
        }
        return UUID.fromString(trimmed).toString();
    }

    private static String principalFingerprint(String userId, String domain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigestField(digest, domain);
            updateDigestField(digest, userId);
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

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
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

        private synchronized void claimExecution() {
            if (executionClaimed) {
                throw conflict("FORWARD_TASK_CREATE_SCOPE_ALREADY_USED");
            }
            executionClaimed = true;
        }

        private void requireClientRequest(@Nullable String actualClientRequestId) {
            if (!clientRequestId.equals(actualClientRequestId)) {
                throw conflict("FORWARD_TASK_CREATE_SCOPE_REQUEST_ID_CONFLICT");
            }
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

    private enum NavigatorCredentialSource {
        BEARER(AuthorizationCredentialLane.NAVIGATOR_JWT, JWT_FINGERPRINT_DOMAIN),
        QUERY_TOKEN(AuthorizationCredentialLane.NAVIGATOR_JWT, JWT_FINGERPRINT_DOMAIN),
        API_KEY(AuthorizationCredentialLane.NAVIGATOR_API_KEY, API_KEY_FINGERPRINT_DOMAIN);

        private final AuthorizationCredentialLane lane;
        private final String fingerprintDomain;

        NavigatorCredentialSource(
                AuthorizationCredentialLane lane,
                String fingerprintDomain) {
            this.lane = lane;
            this.fingerprintDomain = fingerprintDomain;
        }
    }

    private record TrustedIngress(
            CanonicalCommandEnvelope.CommandIngress commandIngress,
            String clientSurface,
            String routeId,
            String ownerUserId,
            @Nullable String tenantId,
            NavigatorCredentialSource credentialSource,
            boolean deferredLegacy) {
    }
}
