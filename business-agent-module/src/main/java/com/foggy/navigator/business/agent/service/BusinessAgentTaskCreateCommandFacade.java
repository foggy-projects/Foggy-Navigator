package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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

/** Trusted MVC composition boundary for canonical Business Task creation. */
@Service
public final class BusinessAgentTaskCreateCommandFacade {

    static final String TASK_ROUTE = "/api/v1/business-agent/tasks";
    static final String CLIENT_SURFACE = "NAVIGATOR_BUSINESS_AGENT";

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

    private final BusinessAgentTaskService taskService;
    private final BusinessAgentSessionService sessionService;
    private final BusinessAgentTaskCreateCommandCoordinator commandCoordinator;
    private final VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;

    public BusinessAgentTaskCreateCommandFacade(
            BusinessAgentTaskService taskService,
            BusinessAgentSessionService sessionService,
            BusinessAgentTaskCreateCommandCoordinator commandCoordinator,
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority) {
        this.taskService = Objects.requireNonNull(taskService, "taskService must not be null");
        this.sessionService = Objects.requireNonNull(
                sessionService, "sessionService must not be null");
        this.commandCoordinator = Objects.requireNonNull(
                commandCoordinator, "commandCoordinator must not be null");
        this.serverAuthority = Objects.requireNonNull(
                serverAuthority, "serverAuthority must not be null");
    }

    public CreatedBusinessAgentTaskDTO createTask(
            @Nullable String suppliedClientRequestId,
            CreateBusinessAgentTaskForm form) {
        TrustedIngress ingress = requireTrustedIngress();
        String clientRequestId = canonicalClientRequestId(suppliedClientRequestId);
        BusinessAgentTaskPreparedFreshCreate prepared = taskService.prepareFreshCreate(
                ingress.tenantId(), ingress.ownerUserId(), form);
        requirePlanOwner(prepared.plan(), ingress);
        TrustedIngress recheckedIngress = requireTrustedIngress();
        if (!ingress.equals(recheckedIngress)) {
            throw rejected("BUSINESS_TASK_CREATE_AUTH_CONTEXT_DRIFT");
        }

        BusinessAgentTaskCreateCommandCoordinator.PlanBinding planBinding =
                BusinessAgentTaskCreateCommandCoordinator.PlanBinding.from(prepared);
        CanonicalCommandEnvelope.CommandBinding binding =
                new CanonicalCommandEnvelope.CommandBinding(
                        CanonicalCommandEnvelope.CommandKind.CREATE,
                        new CanonicalCommandEnvelope.Ingress(
                                CanonicalCommandEnvelope.CommandIngress.DIRECT,
                                CLIENT_SURFACE,
                                TASK_ROUTE),
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
                        planBinding.ownership(),
                        planBinding.target(),
                        planBinding.effect());
        VerifiedCommandAuthorizationDecision decision = serverAuthority.issue(binding);
        CanonicalCommandEnvelope envelope = new CanonicalCommandEnvelope(
                CanonicalCommandEnvelope.SCHEMA_VERSION,
                binding,
                decision.metadata());

        BusinessAgentTaskCreateCommandCoordinator.BusinessTaskCreateCommandResult result =
                commandCoordinator.execute(prepared, envelope, decision);
        if (result instanceof BusinessAgentTaskCreateCommandCoordinator.Executed executed) {
            return executed.freshTask();
        }
        if (result instanceof BusinessAgentTaskCreateCommandCoordinator.RecordedReplay replay) {
            return hydrateRecordedReplay(replay.reference(), prepared);
        }
        throw conflict("BUSINESS_TASK_CREATE_COMMAND_RESULT_UNSUPPORTED");
    }

    private CreatedBusinessAgentTaskDTO hydrateRecordedReplay(
            BusinessAgentTaskCreateCommandCoordinator.BusinessTaskReference reference,
            BusinessAgentTaskPreparedFreshCreate prepared) {
        BusinessAgentTaskCreatePlan plan = prepared.plan();
        BusinessAgentTaskCreatePlan.Identity identity = plan.identity();
        BusinessAgentTaskDTO task = Objects.requireNonNull(
                taskService.getTask(identity.tenantId(), reference.taskId()),
                "recorded Business Task must not be null");
        requireExactRecordedTask(task, reference, plan);

        String contextId = requireText(
                sessionService.resolveReusableContextId(
                        identity.tenantId(),
                        identity.clientAppId(),
                        identity.upstreamUserId(),
                        prepared.input().contextId(),
                        identity.sessionId()),
                "BUSINESS_TASK_CREATE_RECORDED_CONTEXT_MISSING");
        BusinessAgentSessionDTO session = Objects.requireNonNull(
                sessionService.getSession(
                        identity.tenantId(),
                        identity.clientAppId(),
                        identity.upstreamUserId(),
                        contextId),
                "recorded Business Session must not be null");
        requireExactRecordedSession(session, contextId, plan);
        return copyRecordedTask(task, contextId);
    }

    private static void requireExactRecordedTask(
            BusinessAgentTaskDTO task,
            BusinessAgentTaskCreateCommandCoordinator.BusinessTaskReference reference,
            BusinessAgentTaskCreatePlan plan) {
        BusinessAgentTaskCreatePlan.Identity identity = plan.identity();
        BusinessAgentTaskCreatePlan.AgentRoute route = plan.agentRoute();
        BusinessAgentTaskCreatePlan.ModelTarget model = plan.modelTarget();
        BusinessAgentTaskCreatePlan.WorkspaceTarget workspace = plan.workspaceTarget();
        BusinessAgentTaskCreatePlan.InputBinding input = plan.inputBinding();

        requireExact(task.getTaskId(), reference.taskId(), "TASK_ID");
        requireExact(task.getTenantId(), identity.tenantId(), "TENANT");
        requireExact(task.getNavigatorEffectiveUserId(), identity.actorUserId(), "ACTOR");
        requireExact(task.getClientAppId(), identity.clientAppId(), "CLIENT_APP");
        requireExact(task.getUpstreamUserId(), identity.upstreamUserId(), "UPSTREAM_USER");
        requireExact(task.getSessionId(), identity.sessionId(), "SESSION");
        requireExact(task.getAgentId(), route.agentId(), "AGENT");
        requireExact(task.getSkillId(), route.skillId(), "SKILL");
        requireExact(task.getWorkerPoolId(), route.internalWorkerRouteId(), "WORKER_ROUTE");
        requireExact(
                task.getDirectoryId(),
                workspace != null ? workspace.directoryId() : null,
                "DIRECTORY");
        requireExact(task.getModelConfigId(), model.modelConfigId(), "MODEL_CONFIG");
        requireExact(task.getModel(), model.modelName(), "MODEL");
        requireExact(
                task.getRequestedModelConfigId(),
                input.requestedModelConfigIdRaw(),
                "REQUESTED_MODEL_CONFIG");
        requireExact(
                trimToNull(task.getRequestedModelVariant()),
                trimToNull(input.requestedModelVariant()),
                "REQUESTED_MODEL_VARIANT");

        if (route.launcherType() == null) {
            requireExact(task.getWorkerId(), null, "WORKER");
            requireExact(task.getWorkerProviderType(), null, "PROVIDER");
            requireExact(task.getWorkerTaskId(), null, "WORKER_TASK");
            requireExact(task.getWorkerSessionId(), null, "WORKER_SESSION");
        } else {
            requireExact(trimToNull(task.getWorkerId()), route.selectedWorkerId(), "WORKER");
            requireExact(
                    task.getWorkerProviderType(), route.expectedProviderType(), "PROVIDER");
            requireText(
                    task.getWorkerTaskId(),
                    "BUSINESS_TASK_CREATE_RECORDED_WORKER_TASK_MISSING");
        }
    }

    private static void requireExactRecordedSession(
            BusinessAgentSessionDTO session,
            String contextId,
            BusinessAgentTaskCreatePlan plan) {
        BusinessAgentTaskCreatePlan.Identity identity = plan.identity();
        BusinessAgentTaskCreatePlan.AgentRoute route = plan.agentRoute();
        BusinessAgentTaskCreatePlan.WorkspaceTarget workspace = plan.workspaceTarget();

        requireExact(session.getTenantId(), identity.tenantId(), "SESSION_TENANT");
        requireExact(session.getClientAppId(), identity.clientAppId(), "SESSION_CLIENT_APP");
        requireExact(
                session.getUpstreamUserId(),
                identity.upstreamUserId(),
                "SESSION_UPSTREAM_USER");
        requireExact(session.getSessionId(), identity.sessionId(), "SESSION_ID");
        requireExact(session.getContextId(), contextId, "SESSION_CONTEXT");
        requireExact(session.getAgentId(), route.agentId(), "SESSION_AGENT");
        requireExact(session.getSkillId(), route.skillId(), "SESSION_SKILL");
        requireExact(
                session.getDirectoryId(),
                workspace != null ? workspace.directoryId() : null,
                "SESSION_DIRECTORY");
        requireExact(
                session.getModelConfigId(),
                plan.modelTarget().modelConfigId(),
                "SESSION_MODEL_CONFIG");
    }

    private static CreatedBusinessAgentTaskDTO copyRecordedTask(
            BusinessAgentTaskDTO task,
            String contextId) {
        CreatedBusinessAgentTaskDTO replay = new CreatedBusinessAgentTaskDTO();
        replay.setTaskId(task.getTaskId());
        replay.setSessionId(task.getSessionId());
        replay.setContextId(contextId);
        replay.setTenantId(task.getTenantId());
        replay.setClientAppId(task.getClientAppId());
        replay.setUpstreamUserId(task.getUpstreamUserId());
        replay.setNavigatorEffectiveUserId(task.getNavigatorEffectiveUserId());
        replay.setAgentId(task.getAgentId());
        replay.setSkillId(task.getSkillId());
        replay.setWorkerPoolId(task.getWorkerPoolId());
        replay.setDirectoryId(task.getDirectoryId());
        replay.setWorkerTaskId(task.getWorkerTaskId());
        replay.setWorkerSessionId(task.getWorkerSessionId());
        replay.setWorkerId(task.getWorkerId());
        replay.setWorkerProviderType(task.getWorkerProviderType());
        replay.setModelConfigId(task.getModelConfigId());
        replay.setRequestedModelConfigId(task.getRequestedModelConfigId());
        replay.setModel(task.getModel());
        replay.setRequestedModelVariant(task.getRequestedModelVariant());
        replay.setStatus(task.getStatus());
        replay.setCreatedAt(task.getCreatedAt());
        replay.setUpdatedAt(task.getUpdatedAt());
        replay.setTaskScopedToken(null);
        return replay;
    }

    private static void requirePlanOwner(
            BusinessAgentTaskCreatePlan plan,
            TrustedIngress ingress) {
        BusinessAgentTaskCreatePlan.Identity identity = plan.identity();
        if (!ingress.tenantId().equals(identity.tenantId())
                || !ingress.ownerUserId().equals(identity.actorUserId())) {
            throw rejected("BUSINESS_TASK_CREATE_PLAN_OWNER_CONFLICT");
        }
    }

    private static TrustedIngress requireTrustedIngress() {
        HttpServletRequest request = Objects.requireNonNull(
                currentServletRequest(), "trusted Navigator MVC request is unavailable");
        NavigatorCredentialSource credentialSource = requireNavigatorCredentialSource(request);
        rejectForeignCredentials(request, credentialSource);
        CurrentUser currentUser = UserContext.getCurrentUser();
        if (currentUser == null
                || isBlank(currentUser.getUserId())
                || isBlank(currentUser.getTenantId())) {
            throw rejected("BUSINESS_TASK_CREATE_CURRENT_USER_MISSING");
        }
        if (!currentUser.isTenantAdmin() && !currentUser.isSuperAdmin()) {
            throw rejected("BUSINESS_TASK_CREATE_ROLE_REJECTED");
        }
        requireAttribute(request, "userId", currentUser.getUserId());
        requireAttribute(request, "username", currentUser.getUsername());
        requireAttribute(request, "tenantId", currentUser.getTenantId());
        requireAttribute(request, "roles", currentUser.getRoles());
        if (!"POST".equals(request.getMethod())) {
            throw rejected("BUSINESS_TASK_CREATE_HTTP_METHOD_CONFLICT");
        }
        Object routeAttribute = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = routeAttribute == null ? null : routeAttribute.toString();
        if (!TASK_ROUTE.equals(route)) {
            throw rejected("BUSINESS_TASK_CREATE_ROUTE_CONFLICT");
        }
        return new TrustedIngress(
                currentUser.getUserId(),
                currentUser.getUsername(),
                currentUser.getTenantId(),
                currentUser.getRoles(),
                credentialSource);
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
            throw rejected("BUSINESS_TASK_CREATE_MIXED_AUTHORIZATION");
        }
        if (bearerCandidate) {
            if (isBlank(authorization.substring(BEARER_PREFIX.length()))) {
                throw rejected("BUSINESS_TASK_CREATE_BEARER_MISSING");
            }
            return NavigatorCredentialSource.BEARER;
        }
        if (queryCandidate) {
            if (isBlank(queryToken)) {
                throw rejected("BUSINESS_TASK_CREATE_QUERY_TOKEN_MISSING");
            }
            return NavigatorCredentialSource.QUERY_TOKEN;
        }
        if (apiKeyCandidate) {
            if (isBlank(apiKey)) {
                throw rejected("BUSINESS_TASK_CREATE_API_KEY_MISSING");
            }
            return NavigatorCredentialSource.API_KEY;
        }
        throw rejected("BUSINESS_TASK_CREATE_CREDENTIAL_SOURCE_MISSING");
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
            throw rejected("BUSINESS_TASK_CREATE_MIXED_AUTHORIZATION");
        }
        for (String header : FOREIGN_CREDENTIAL_HEADERS) {
            if (!isBlank(request.getHeader(header))) {
                throw rejected("BUSINESS_TASK_CREATE_MIXED_CREDENTIAL_LANE");
            }
        }
    }

    private static void requireAttribute(
            HttpServletRequest request,
            String name,
            @Nullable String expected) {
        if (!Objects.equals(expected, request.getAttribute(name))) {
            throw rejected("BUSINESS_TASK_CREATE_AUTH_ATTRIBUTE_CONFLICT");
        }
    }

    private static String canonicalClientRequestId(@Nullable String supplied) {
        if (supplied == null) {
            return UUID.randomUUID().toString();
        }
        if (supplied.isBlank()) {
            throw new IllegalArgumentException("clientRequestId must not be blank when supplied");
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

    private static void requireExact(
            @Nullable String actual,
            @Nullable String expected,
            String field) {
        if (!Objects.equals(actual, expected)) {
            throw conflict("BUSINESS_TASK_CREATE_RECORDED_" + field + "_CONFLICT");
        }
    }

    private static String requireText(@Nullable String value, String safeCode) {
        if (isBlank(value)) {
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

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    @Nullable
    private static HttpServletRequest currentServletRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest() : null;
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
            String ownerUserId,
            String username,
            String tenantId,
            String roles,
            NavigatorCredentialSource credentialSource) {
    }
}
