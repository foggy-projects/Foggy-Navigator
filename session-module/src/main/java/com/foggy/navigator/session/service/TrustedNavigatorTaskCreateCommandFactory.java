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
import java.util.regex.Pattern;

/**
 * Server-owned command factory and penultimate submit stage for trusted
 * Navigator MVC JWT task creation.
 */
@Service
public final class TrustedNavigatorTaskCreateCommandFactory
        implements AgentSubmitPipelineStage {

    static final String TASK_ROUTE = "/api/v1/tasks";
    static final String AGENT_ASK_ROUTE = "/api/v1/agents/{agentId}/ask";
    static final String UI_SOURCE = "UI";
    static final String A2A_SOURCE = "A2A";
    static final String UI_SURFACE = "NAVIGATOR_UI";
    static final String A2A_SURFACE = "NAVIGATOR_A2A";

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

    @Override
    public boolean supports(AgentTaskSubmitRequest request) {
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
        TrustedIngress ingress = requireTrustedIngress(request);
        if (ingress.deferredLegacy()) {
            return chain.proceed(request);
        }

        String clientRequestId = canonicalClientRequestId(request.getClientRequestId());
        request.setClientRequestId(clientRequestId);
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
                                clientRequestId,
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

        TaskCreateCommandCoordinator.TaskCreateCommandResult result =
                commandCoordinator.execute(
                        dispatchRequest, context, plan, envelope, decision);
        DispatchTaskDTO dispatchTask;
        if (result instanceof TaskCreateCommandCoordinator.Executed executed) {
            dispatchTask = executed.freshTask();
        } else if (result instanceof TaskCreateCommandCoordinator.RecordedReplay replay) {
            dispatchTask = hydrateRecordedTask(replay.reference(), context, plan);
        } else {
            throw conflict("TASK_CREATE_COMMAND_RESULT_MISSING");
        }
        return AgentTaskSubmitResult.of(
                taskDispatchFacade.toA2aTask(dispatchTask), dispatchTask);
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

    private TrustedIngress requireTrustedIngress(AgentTaskSubmitRequest request) {
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
