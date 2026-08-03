package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessAgentTaskCreateCommandFacadeTest {

    private static final Instant NOW = Instant.parse("2026-08-04T01:00:00Z");
    private static final LocalDateTime LOCAL_NOW =
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String CANONICAL_REQUEST_ID =
            "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private BusinessAgentTaskService taskService;
    @Mock
    private BusinessAgentSessionService sessionService;
    @Mock
    private BusinessAgentTaskCreateCommandCoordinator commandCoordinator;

    private VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private BusinessAgentTaskCreateCommandFacade facade;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        serverAuthority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                "test-policy",
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));
        facade = new BusinessAgentTaskCreateCommandFacade(
                taskService, sessionService, commandCoordinator, serverAuthority);
        installBearerUser("TENANT_ADMIN");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        UserContext.clear();
    }

    @Test
    void freshCreateUsesOnlyTrustedJwtAndReturnsExactOneTimeResult() {
        CreateBusinessAgentTaskForm form = form(null);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(form, "actor-1");
        CreatedBusinessAgentTaskDTO fresh = createdTask(prepared.plan(), "CREATED");
        fresh.setTaskScopedToken("one-time-token-must-stay-in-memory");
        when(taskService.prepareFreshCreate("tenant-1", "actor-1", form))
                .thenReturn(prepared);
        when(commandCoordinator.execute(eq(prepared), any(), any()))
                .thenReturn(new BusinessAgentTaskCreateCommandCoordinator.Executed(
                        new BusinessAgentTaskCreateCommandCoordinator.BusinessTaskReference(
                                "business-task-1"),
                        fresh));

        CreatedBusinessAgentTaskDTO actual = facade.createTask(
                " 550E8400-E29B-41D4-A716-446655440000 ", form);

        assertSame(fresh, actual);
        ArgumentCaptor<CanonicalCommandEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        ArgumentCaptor<VerifiedCommandAuthorizationDecision> decisionCaptor =
                ArgumentCaptor.forClass(VerifiedCommandAuthorizationDecision.class);
        verify(commandCoordinator).execute(
                eq(prepared), envelopeCaptor.capture(), decisionCaptor.capture());
        CanonicalCommandEnvelope envelope = envelopeCaptor.getValue();
        assertEquals(
                envelope.binding(),
                serverAuthority.requireVerified(envelope, decisionCaptor.getValue()));
        assertEquals(CanonicalCommandEnvelope.CommandKind.CREATE,
                envelope.binding().commandKind());
        assertEquals(CanonicalCommandEnvelope.CommandIngress.DIRECT,
                envelope.binding().ingress().ingress());
        assertEquals(BusinessAgentTaskCreateCommandFacade.CLIENT_SURFACE,
                envelope.binding().ingress().clientSurface());
        assertEquals(BusinessAgentTaskCreateCommandFacade.TASK_ROUTE,
                envelope.binding().ingress().routeId());
        assertEquals(CANONICAL_REQUEST_ID,
                envelope.binding().request().clientRequestId());
        assertEquals(CANONICAL_REQUEST_ID,
                envelope.binding().request().idempotencyKey());
        assertEquals(CANONICAL_REQUEST_ID,
                envelope.binding().request().correlationId());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_JWT,
                envelope.binding().actor().lane());
        assertEquals("actor-1", envelope.binding().ownership().ownerReference());
        assertEquals("app-1", envelope.binding().ownership().clientAppReference());
        String safeEnvelope = envelope.toString();
        assertFalse(safeEnvelope.contains("jwt-secret"));
        assertFalse(safeEnvelope.contains("client-context-must-not-escape"));
        assertFalse(safeEnvelope.contains("one-time-token-must-stay-in-memory"));
        verifyNoInteractions(sessionService);
    }

    @Test
    void queryTokenAndApiKeyKeepTheirExactCredentialLanesWithoutCredentialHashing() {
        CreateBusinessAgentTaskForm form = form(null);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(form, "actor-1");
        CreatedBusinessAgentTaskDTO fresh = createdTask(prepared.plan(), "CREATED");
        when(taskService.prepareFreshCreate("tenant-1", "actor-1", form))
                .thenReturn(prepared);
        when(commandCoordinator.execute(eq(prepared), any(), any()))
                .thenReturn(new BusinessAgentTaskCreateCommandCoordinator.Executed(
                        new BusinessAgentTaskCreateCommandCoordinator.BusinessTaskReference(
                                "business-task-1"),
                        fresh));

        installQueryTokenUser("TENANT_ADMIN");
        facade.createTask(CANONICAL_REQUEST_ID, form);
        installApiKeyUser("SUPER_ADMIN");
        facade.createTask(CANONICAL_REQUEST_ID, form);

        ArgumentCaptor<CanonicalCommandEnvelope> captor =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator, org.mockito.Mockito.times(2))
                .execute(eq(prepared), captor.capture(), any());
        List<CanonicalCommandEnvelope> envelopes = captor.getAllValues();
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_JWT,
                envelopes.get(0).binding().actor().lane());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_API_KEY,
                envelopes.get(1).binding().actor().lane());
        assertNotEquals(
                envelopes.get(0).binding().actor().fingerprint(),
                envelopes.get(1).binding().actor().fingerprint());
        assertFalse(envelopes.get(0).toString().contains("query-secret"));
        assertFalse(envelopes.get(1).toString().contains("api-key-secret"));
    }

    @Test
    void absentRequestIdsAreFreshWhileExplicitBlankOrInvalidIdsFailBeforeRead() {
        CreateBusinessAgentTaskForm form = form(null);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(form, "actor-1");
        CreatedBusinessAgentTaskDTO fresh = createdTask(prepared.plan(), "CREATED");
        when(taskService.prepareFreshCreate("tenant-1", "actor-1", form))
                .thenReturn(prepared);
        when(commandCoordinator.execute(eq(prepared), any(), any()))
                .thenReturn(new BusinessAgentTaskCreateCommandCoordinator.Executed(
                        new BusinessAgentTaskCreateCommandCoordinator.BusinessTaskReference(
                                "business-task-1"),
                        fresh));

        facade.createTask(null, form);
        facade.createTask(null, form);

        ArgumentCaptor<CanonicalCommandEnvelope> captor =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator, org.mockito.Mockito.times(2))
                .execute(eq(prepared), captor.capture(), any());
        String first = captor.getAllValues().get(0).binding().request().clientRequestId();
        String second = captor.getAllValues().get(1).binding().request().clientRequestId();
        assertNotEquals(first, second);
        assertEquals(first, java.util.UUID.fromString(first).toString());
        assertEquals(second, java.util.UUID.fromString(second).toString());

        reset(taskService, sessionService, commandCoordinator);
        for (String invalid : List.of("", "   ", "not-a-uuid")) {
            assertThrows(IllegalArgumentException.class, () -> facade.createTask(invalid, form));
        }
        verifyNoInteractions(taskService, sessionService, commandCoordinator);
    }

    @Test
    void untrustedMvcVariantsAllFailBeforeAnyBusinessRead() {
        assertRejectedBeforeRead(() -> request.setMethod("GET"));
        assertRejectedBeforeRead(() -> request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/tasks"));
        assertRejectedBeforeRead(() -> request.setAttribute("tenantId", "tenant-drift"));
        assertRejectedBeforeRead(() -> request.addHeader("X-API-Key", "mixed-api-key"));
        assertRejectedBeforeRead(() -> request.addHeader("X-Task-Token", "foreign-task-token"));
        assertRejectedBeforeRead(() -> {
            request.removeHeader("Authorization");
            request.addHeader("Authorization", "Basic foreign-credential");
        });
        assertRejectedBeforeRead(() -> {
            CurrentUser user = currentUser("DEVELOPER");
            UserContext.setCurrentUser(user);
            setAuthAttributes(request, user);
        });
        assertRejectedBeforeRead(UserContext::clear);
    }

    @Test
    void planOwnerAndPostPlanAmbientAuthDriftFailBeforeCoordinator() {
        CreateBusinessAgentTaskForm form = form(null);
        BusinessAgentTaskPreparedFreshCreate wrongOwner = prepared(form, "actor-other");
        when(taskService.prepareFreshCreate("tenant-1", "actor-1", form))
                .thenReturn(wrongOwner);

        assertThrows(SecurityException.class,
                () -> facade.createTask(CANONICAL_REQUEST_ID, form));
        verifyNoInteractions(commandCoordinator, sessionService);

        reset(taskService, sessionService, commandCoordinator);
        installBearerUser("TENANT_ADMIN");
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(form, "actor-1");
        when(taskService.prepareFreshCreate("tenant-1", "actor-1", form))
                .thenAnswer(invocation -> {
                    request.setAttribute("roles", "DEVELOPER");
                    return prepared;
                });

        assertThrows(SecurityException.class,
                () -> facade.createTask(CANONICAL_REQUEST_ID, form));
        verifyNoInteractions(commandCoordinator, sessionService);
    }

    @Test
    void recordedReplayHydratesOnlyStableTaskAndSessionFactsAndNeverReturnsToken() {
        CreateBusinessAgentTaskForm form = form(null);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(form, "actor-1");
        BusinessAgentTaskCreatePlan plan = prepared.plan();
        BusinessAgentTaskDTO task = recordedTask(plan);
        task.setContextId("task-context-must-be-ignored");
        task.setStatus("COMPLETED");
        task.setCreatedAt(LOCAL_NOW.minusMinutes(5));
        task.setUpdatedAt(LOCAL_NOW);
        BusinessAgentSessionDTO session = recordedSession(plan, "bctx-generated");
        session.setLatestTaskId("later-task-is-mutable");
        session.setWorkerId("later-worker-is-mutable");
        session.setWorkerProviderType("later-provider-is-mutable");
        session.setStatus("later-status-is-mutable");
        session.setClientContextJson("client-context-must-not-escape");
        stubRecordedReplay(form, prepared, task, session, "bctx-generated");

        CreatedBusinessAgentTaskDTO replay =
                facade.createTask(CANONICAL_REQUEST_ID, form);

        assertEquals("business-task-1", replay.getTaskId());
        assertEquals("bctx-generated", replay.getContextId());
        assertEquals("COMPLETED", replay.getStatus());
        assertEquals(task.getCreatedAt(), replay.getCreatedAt());
        assertEquals(task.getUpdatedAt(), replay.getUpdatedAt());
        assertNull(replay.getTaskScopedToken());
        InOrder order = inOrder(commandCoordinator, taskService, sessionService);
        order.verify(commandCoordinator).execute(eq(prepared), any(), any());
        order.verify(taskService).getTask("tenant-1", "business-task-1");
        order.verify(sessionService).resolveReusableContextId(
                "tenant-1", "app-1", "upstream-user-1", null, "session-1");
        order.verify(sessionService).getSession(
                "tenant-1", "app-1", "upstream-user-1", "bctx-generated");
    }

    @Test
    void everyStableRecordedTaskDriftFailsBeforeSessionRead() {
        CreateBusinessAgentTaskForm form = form(null);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(form, "actor-1");
        List<Consumer<BusinessAgentTaskDTO>> drifts = List.of(
                task -> task.setTaskId("task-drift"),
                task -> task.setTenantId("tenant-drift"),
                task -> task.setNavigatorEffectiveUserId("actor-drift"),
                task -> task.setClientAppId("app-drift"),
                task -> task.setUpstreamUserId("upstream-drift"),
                task -> task.setSessionId("session-drift"),
                task -> task.setAgentId("agent-drift"),
                task -> task.setSkillId("skill-drift"),
                task -> task.setWorkerPoolId("route-drift"),
                task -> task.setDirectoryId("directory-drift"),
                task -> task.setModelConfigId("model-config-drift"),
                task -> task.setModel("model-drift"),
                task -> task.setRequestedModelConfigId("requested-model-drift"),
                task -> task.setRequestedModelVariant("variant-drift"),
                task -> task.setWorkerId("worker-drift"),
                task -> task.setWorkerProviderType("provider-drift"),
                task -> task.setWorkerTaskId(null));

        for (Consumer<BusinessAgentTaskDTO> drift : drifts) {
            reset(taskService, sessionService, commandCoordinator);
            installBearerUser("TENANT_ADMIN");
            BusinessAgentTaskDTO task = recordedTask(prepared.plan());
            drift.accept(task);
            stubCoordinatorReplay(form, prepared);
            when(taskService.getTask("tenant-1", "business-task-1")).thenReturn(task);

            assertThrows(IllegalStateException.class,
                    () -> facade.createTask(CANONICAL_REQUEST_ID, form));
            verifyNoInteractions(sessionService);
        }
    }

    @Test
    void everyStableRecordedSessionDriftFailsWhileMutableProjectionIsIgnored() {
        CreateBusinessAgentTaskForm form = form(null);
        BusinessAgentTaskPreparedFreshCreate prepared = prepared(form, "actor-1");
        List<Consumer<BusinessAgentSessionDTO>> drifts = List.of(
                session -> session.setTenantId("tenant-drift"),
                session -> session.setClientAppId("app-drift"),
                session -> session.setUpstreamUserId("upstream-drift"),
                session -> session.setSessionId("session-drift"),
                session -> session.setContextId("context-drift"),
                session -> session.setAgentId("agent-drift"),
                session -> session.setSkillId("skill-drift"),
                session -> session.setDirectoryId("directory-drift"),
                session -> session.setModelConfigId("model-drift"));

        for (Consumer<BusinessAgentSessionDTO> drift : drifts) {
            reset(taskService, sessionService, commandCoordinator);
            installBearerUser("TENANT_ADMIN");
            BusinessAgentTaskDTO task = recordedTask(prepared.plan());
            BusinessAgentSessionDTO session = recordedSession(
                    prepared.plan(), "bctx-generated");
            drift.accept(session);
            stubRecordedReplay(form, prepared, task, session, "bctx-generated");

            assertThrows(IllegalStateException.class,
                    () -> facade.createTask(CANONICAL_REQUEST_ID, form));
        }
    }

    private void assertRejectedBeforeRead(Runnable mutation) {
        reset(taskService, sessionService, commandCoordinator);
        installBearerUser("TENANT_ADMIN");
        mutation.run();
        assertThrows(RuntimeException.class,
                () -> facade.createTask(CANONICAL_REQUEST_ID, form(null)));
        verifyNoInteractions(taskService, sessionService, commandCoordinator);
    }

    private void stubRecordedReplay(
            CreateBusinessAgentTaskForm form,
            BusinessAgentTaskPreparedFreshCreate prepared,
            BusinessAgentTaskDTO task,
            BusinessAgentSessionDTO session,
            String contextId) {
        stubCoordinatorReplay(form, prepared);
        when(taskService.getTask("tenant-1", "business-task-1")).thenReturn(task);
        when(sessionService.resolveReusableContextId(
                "tenant-1", "app-1", "upstream-user-1", form.getContextId(), "session-1"))
                .thenReturn(contextId);
        when(sessionService.getSession(
                "tenant-1", "app-1", "upstream-user-1", contextId))
                .thenReturn(session);
    }

    private void stubCoordinatorReplay(
            CreateBusinessAgentTaskForm form,
            BusinessAgentTaskPreparedFreshCreate prepared) {
        when(taskService.prepareFreshCreate("tenant-1", "actor-1", form))
                .thenReturn(prepared);
        when(commandCoordinator.execute(eq(prepared), any(), any()))
                .thenReturn(new BusinessAgentTaskCreateCommandCoordinator.RecordedReplay(
                        new BusinessAgentTaskCreateCommandCoordinator.BusinessTaskReference(
                                "business-task-1")));
    }

    private void installBearerUser(String roles) {
        installUser(roles);
        request.addHeader("Authorization", "Bearer jwt-secret");
    }

    private void installQueryTokenUser(String roles) {
        installUser(roles);
        request.setParameter("token", "query-secret");
    }

    private void installApiKeyUser(String roles) {
        installUser(roles);
        request.addHeader("X-API-Key", "api-key-secret");
    }

    private void installUser(String roles) {
        request = new MockHttpServletRequest("POST", BusinessAgentTaskCreateCommandFacade.TASK_ROUTE);
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                BusinessAgentTaskCreateCommandFacade.TASK_ROUTE);
        CurrentUser user = currentUser(roles);
        UserContext.setCurrentUser(user);
        setAuthAttributes(request, user);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private CurrentUser currentUser(String roles) {
        return CurrentUser.builder()
                .userId("actor-1")
                .username("admin-1")
                .tenantId("tenant-1")
                .roles(roles)
                .build();
    }

    private void setAuthAttributes(MockHttpServletRequest request, CurrentUser user) {
        request.setAttribute("userId", user.getUserId());
        request.setAttribute("username", user.getUsername());
        request.setAttribute("tenantId", user.getTenantId());
        request.setAttribute("roles", user.getRoles());
    }

    private CreateBusinessAgentTaskForm form(String contextId) {
        CreateBusinessAgentTaskForm form = new CreateBusinessAgentTaskForm();
        form.setClientAppId("app-1");
        form.setSessionId("session-1");
        form.setContextId(contextId);
        form.setUpstreamUserId("upstream-user-1");
        form.setAgentId("agent-1");
        form.setDirectoryId("directory-1");
        form.setRequestedModelConfigId("requested-model-1");
        form.setModelVariant(" variant-1 ");
        form.setAllowedTools(List.of("read_file"));
        form.setClientContextJson("client-context-must-not-escape");
        return form;
    }

    private BusinessAgentTaskPreparedFreshCreate prepared(
            CreateBusinessAgentTaskForm form,
            String actorUserId) {
        return new BusinessAgentTaskPreparedFreshCreate(
                plan(actorUserId),
                BusinessAgentTaskCreateInput.snapshot(form));
    }

    private BusinessAgentTaskCreatePlan plan(String actorUserId) {
        return new BusinessAgentTaskCreatePlan(
                new BusinessAgentTaskCreatePlan.Identity(
                        "tenant-1",
                        actorUserId,
                        "app-1",
                        "upstream-system-1",
                        "upstream-user-1",
                        "session-1",
                        null),
                new BusinessAgentTaskCreatePlan.AgentRoute(
                        "agent-1",
                        ResourceOwnerType.CLIENT_APP,
                        "app-1",
                        "app-1",
                        "AGENT:CLIENT_APP",
                        "skill-1",
                        "skill-1",
                        "pool-1",
                        "pool-1",
                        ResourceOwnerType.PLATFORM,
                        "tenant-1",
                        "WORKER_POOL:PLATFORM",
                        "LANGGRAPH_BIZ",
                        "worker-1",
                        ResourceOwnerType.UPSTREAM_SYSTEM,
                        "upstream-system-1",
                        "BIZ_WORKER_IDENTITY",
                        "worker-1",
                        "worker-1",
                        "WORKER:LANGGRAPH_BIZ",
                        "langgraph-biz-worker"),
                new BusinessAgentTaskCreatePlan.ModelTarget(
                        "model-config-1",
                        "qwen-plus",
                        null,
                        "requested-model-1",
                        "variant-1",
                        LlmModelCategory.GENERAL,
                        "MODEL_CONFIG_DEFAULT",
                        "LANGGRAPH_BIZ",
                        "AGENT_DEFAULT_MODEL:REQUESTED_MODEL_GRANT"),
                new BusinessAgentTaskCreatePlan.WorkspaceTarget(
                        "directory-1",
                        "worker-1",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.DELEGATED,
                        "/workspace/app",
                        List.of("/workspace"),
                        false,
                        "quota-digest",
                        "retention-digest",
                        "concurrency-digest",
                        "WORKING_DIRECTORY:USER_PRIVATE"),
                new BusinessAgentTaskCreatePlan.InputBinding(
                        "requested-model-1",
                        " variant-1 ",
                        "directory-1",
                        List.of("read_file"),
                        "client-context-digest"),
                null);
    }

    private CreatedBusinessAgentTaskDTO createdTask(
            BusinessAgentTaskCreatePlan plan,
            String status) {
        CreatedBusinessAgentTaskDTO task = new CreatedBusinessAgentTaskDTO();
        copyStableTask(task, plan);
        task.setContextId("bctx-generated");
        task.setStatus(status);
        task.setCreatedAt(LOCAL_NOW.minusMinutes(5));
        task.setUpdatedAt(LOCAL_NOW);
        return task;
    }

    private BusinessAgentTaskDTO recordedTask(BusinessAgentTaskCreatePlan plan) {
        BusinessAgentTaskDTO task = new BusinessAgentTaskDTO();
        copyStableTask(task, plan);
        task.setStatus("COMPLETED");
        task.setCreatedAt(LOCAL_NOW.minusMinutes(5));
        task.setUpdatedAt(LOCAL_NOW);
        return task;
    }

    private void copyStableTask(
            BusinessAgentTaskDTO task,
            BusinessAgentTaskCreatePlan plan) {
        task.setTaskId("business-task-1");
        task.setSessionId(plan.identity().sessionId());
        task.setTenantId(plan.identity().tenantId());
        task.setClientAppId(plan.identity().clientAppId());
        task.setUpstreamUserId(plan.identity().upstreamUserId());
        task.setNavigatorEffectiveUserId(plan.identity().actorUserId());
        task.setAgentId(plan.agentRoute().agentId());
        task.setSkillId(plan.agentRoute().skillId());
        task.setWorkerPoolId(plan.agentRoute().internalWorkerRouteId());
        task.setDirectoryId(plan.workspaceTarget().directoryId());
        task.setWorkerTaskId("worker-task-1");
        task.setWorkerSessionId("worker-session-1");
        task.setWorkerId(" worker-1 ");
        task.setWorkerProviderType(plan.agentRoute().expectedProviderType());
        task.setModelConfigId(plan.modelTarget().modelConfigId());
        task.setRequestedModelConfigId(plan.inputBinding().requestedModelConfigIdRaw());
        task.setModel(plan.modelTarget().modelName());
        task.setRequestedModelVariant(" variant-1 ");
    }

    private BusinessAgentSessionDTO recordedSession(
            BusinessAgentTaskCreatePlan plan,
            String contextId) {
        BusinessAgentSessionDTO session = new BusinessAgentSessionDTO();
        session.setTenantId(plan.identity().tenantId());
        session.setClientAppId(plan.identity().clientAppId());
        session.setUpstreamUserId(plan.identity().upstreamUserId());
        session.setSessionId(plan.identity().sessionId());
        session.setContextId(contextId);
        session.setAgentId(plan.agentRoute().agentId());
        session.setSkillId(plan.agentRoute().skillId());
        session.setDirectoryId(plan.workspaceTarget().directoryId());
        session.setModelConfigId(plan.modelTarget().modelConfigId());
        return session;
    }
}
