package com.foggy.navigator.session.service;

import com.foggy.navigator.auth.interceptor.AuthInterceptor;
import com.foggy.navigator.auth.util.JwtUtil;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipelineChain;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipelineStage;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.agent.pipeline.DefaultAgentSubmitPipeline;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrustedNavigatorTaskCreateCommandFactoryTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String USERNAME = "foggy";
    private static final String ROLES = "USER";

    @Mock
    private TaskDispatchFacade taskDispatchFacade;
    @Mock
    private TaskCreateCommandCoordinator commandCoordinator;
    @Mock
    private AgentSubmitPipelineChain chain;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private UserAuthService userAuthService;

    private VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private TrustedNavigatorTaskCreateCommandFactory factory;

    @BeforeEach
    void setUp() {
        serverAuthority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                "test.policy.v1",
                Clock.fixed(Instant.parse("2026-08-03T08:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));
        factory = new TrustedNavigatorTaskCreateCommandFactory(
                taskDispatchFacade, commandCoordinator, serverAuthority);
    }

    @AfterEach
    void cleanRequestContext() {
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void trustedBearerUiBuildsStableServerOwnedBindingAndReturnsFreshTask() throws Exception {
        String suppliedId = " 550E8400-E29B-41D4-A716-446655440000 ";
        BoundRequest first = bindJwt(
                "jwt-one", false, TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        first.submitRequest().setClientRequestId(suppliedId);
        first.submitRequest().setPrompt("first content");
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder().prompt("first content").build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan(USER_ID, TENANT_ID);
        DispatchTaskDTO fresh = exactTask("task-fresh-1");
        A2aTask a2aTask = A2aTask.builder().id("task-fresh-1").build();
        when(taskDispatchFacade.toTaskDispatchRequest(any())).thenReturn(dispatchRequest);
        when(taskDispatchFacade.resolveCreateExecutionPlan(
                same(dispatchRequest), same(first.submitRequest().getResolveContext())))
                .thenReturn(plan);
        when(commandCoordinator.execute(
                same(dispatchRequest), same(first.submitRequest().getResolveContext()),
                same(plan), any(), any()))
                .thenReturn(new TaskCreateCommandCoordinator.Executed(
                        new TaskCreateCommandCoordinator.TaskReference("task-fresh-1"), fresh));
        when(taskDispatchFacade.toA2aTask(same(fresh))).thenReturn(a2aTask);

        AgentTaskSubmitResult result = factory.handle(first.submitRequest(), chain);

        assertSame(fresh, result.getDispatchTask());
        assertSame(a2aTask, result.getTask());
        assertEquals("550e8400-e29b-41d4-a716-446655440000",
                first.submitRequest().getClientRequestId());
        assertEquals(Integer.MAX_VALUE - 1, factory.order());
        assertEquals("trusted-navigator-task-create-command", factory.name());
        ArgumentCaptor<CanonicalCommandEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        ArgumentCaptor<VerifiedCommandAuthorizationDecision> decisionCaptor =
                ArgumentCaptor.forClass(VerifiedCommandAuthorizationDecision.class);
        verify(commandCoordinator).execute(
                same(dispatchRequest), same(first.submitRequest().getResolveContext()),
                same(plan), envelopeCaptor.capture(), decisionCaptor.capture());
        CanonicalCommandEnvelope envelope = envelopeCaptor.getValue();
        CanonicalCommandEnvelope.CommandBinding binding = envelope.binding();
        assertEquals(CanonicalCommandEnvelope.CommandIngress.DIRECT,
                binding.ingress().ingress());
        assertEquals(TrustedNavigatorTaskCreateCommandFactory.UI_SURFACE,
                binding.ingress().clientSurface());
        assertEquals(TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                binding.ingress().routeId());
        assertEquals(binding.request().clientRequestId(), binding.request().idempotencyKey());
        assertEquals(binding.request().clientRequestId(), binding.request().correlationId());
        assertEquals(CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                binding.actor().kind());
        assertEquals(AuthorizationPrincipalType.NAVIGATOR_USER,
                binding.actor().principalType());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_JWT, binding.actor().lane());
        assertEquals(64, binding.actor().fingerprint().length());
        assertFalse(binding.actor().fingerprint().contains("jwt-one"));
        assertEquals(USER_ID, binding.ownership().ownerReference());
        assertEquals(binding, serverAuthority.requireVerified(
                envelope, decisionCaptor.getValue()));
        verifyNoInteractions(chain);

        BoundRequest refreshed = bindJwt(
                "jwt-two", false, TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        refreshed.submitRequest().setClientRequestId(
                first.submitRequest().getClientRequestId());
        refreshed.submitRequest().setPrompt("different business content");
        when(taskDispatchFacade.resolveCreateExecutionPlan(
                same(dispatchRequest), same(refreshed.submitRequest().getResolveContext())))
                .thenReturn(plan);
        when(commandCoordinator.execute(
                same(dispatchRequest), same(refreshed.submitRequest().getResolveContext()),
                same(plan), any(), any()))
                .thenReturn(new TaskCreateCommandCoordinator.Executed(
                        new TaskCreateCommandCoordinator.TaskReference("task-fresh-1"), fresh));

        factory.handle(refreshed.submitRequest(), chain);

        ArgumentCaptor<CanonicalCommandEnvelope> refreshedEnvelopes =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator, times(2)).execute(
                same(dispatchRequest), any(AgentResolveContext.class), same(plan),
                refreshedEnvelopes.capture(), any());
        assertEquals(refreshedEnvelopes.getAllValues().get(0).binding(),
                refreshedEnvelopes.getAllValues().get(1).binding());
        assertNotEquals(refreshedEnvelopes.getAllValues().get(0).authorizationMetadata().decisionId(),
                refreshedEnvelopes.getAllValues().get(1).authorizationMetadata().decisionId());
    }

    @Test
    void trustedQueryTokenA2aMintsOneCanonicalIdAndBindsA2aIngress() throws Exception {
        BoundRequest bound = bindJwt(
                "query-jwt", true, TrustedNavigatorTaskCreateCommandFactory.AGENT_ASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.A2A_SOURCE, USER_ID, TENANT_ID);
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder().build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan(USER_ID, TENANT_ID);
        DispatchTaskDTO fresh = exactTask("task-a2a-1");
        when(taskDispatchFacade.toTaskDispatchRequest(same(bound.submitRequest())))
                .thenReturn(dispatchRequest);
        when(taskDispatchFacade.resolveCreateExecutionPlan(
                same(dispatchRequest), same(bound.submitRequest().getResolveContext())))
                .thenReturn(plan);
        when(commandCoordinator.execute(any(), any(), any(), any(), any()))
                .thenReturn(new TaskCreateCommandCoordinator.Executed(
                        new TaskCreateCommandCoordinator.TaskReference("task-a2a-1"), fresh));
        when(taskDispatchFacade.toA2aTask(same(fresh)))
                .thenReturn(A2aTask.builder().id("task-a2a-1").build());

        factory.handle(bound.submitRequest(), chain);

        String minted = bound.submitRequest().getClientRequestId();
        assertEquals(UUID.fromString(minted).toString(), minted);
        ArgumentCaptor<CanonicalCommandEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator).execute(
                same(dispatchRequest), same(bound.submitRequest().getResolveContext()),
                same(plan), envelopeCaptor.capture(), any());
        assertEquals(CanonicalCommandEnvelope.CommandIngress.A2A,
                envelopeCaptor.getValue().binding().ingress().ingress());
        assertEquals(TrustedNavigatorTaskCreateCommandFactory.A2A_SURFACE,
                envelopeCaptor.getValue().binding().ingress().clientSurface());
        assertEquals(minted,
                envelopeCaptor.getValue().binding().request().clientRequestId());
        assertFalse(envelopeCaptor.getValue().toString().contains("query-jwt"));
        verifyNoInteractions(chain);
    }

    @Test
    void recordedReplayHydratesReadOnlyAndRejectsMissingOrDriftedTask() throws Exception {
        BoundRequest bound = bindJwt(
                "jwt-replay", false, TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        bound.submitRequest().setClientRequestId("550e8400-e29b-41d4-a716-446655440000");
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder().build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan(USER_ID, TENANT_ID);
        TaskCreateCommandCoordinator.TaskReference reference =
                new TaskCreateCommandCoordinator.TaskReference("task-recorded-1");
        DispatchTaskDTO recorded = exactTask("task-recorded-1");
        when(taskDispatchFacade.toTaskDispatchRequest(any())).thenReturn(dispatchRequest);
        when(taskDispatchFacade.resolveCreateExecutionPlan(any(), any())).thenReturn(plan);
        when(commandCoordinator.execute(any(), any(), any(), any(), any()))
                .thenReturn(new TaskCreateCommandCoordinator.RecordedReplay(reference));
        when(taskDispatchFacade.getTask(
                "task-recorded-1", bound.submitRequest().getResolveContext()))
                .thenReturn(Optional.of(recorded));
        when(taskDispatchFacade.toA2aTask(same(recorded)))
                .thenReturn(A2aTask.builder().id("task-recorded-1").build());

        AgentTaskSubmitResult replay = factory.handle(bound.submitRequest(), chain);

        assertSame(recorded, replay.getDispatchTask());
        verify(taskDispatchFacade).getTask(
                "task-recorded-1", bound.submitRequest().getResolveContext());
        verifyNoInteractions(chain);

        when(taskDispatchFacade.getTask(
                "task-recorded-1", bound.submitRequest().getResolveContext()))
                .thenReturn(Optional.empty());
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> factory.handle(bound.submitRequest(), chain));
        assertEquals("TASK_CREATE_RECORDED_TASK_UNAVAILABLE", missing.getMessage());

        DispatchTaskDTO incomplete = exactTask("task-recorded-1");
        incomplete.setProviderType(null);
        when(taskDispatchFacade.getTask(
                "task-recorded-1", bound.submitRequest().getResolveContext()))
                .thenReturn(Optional.of(incomplete));
        IllegalStateException incompleteIdentity = assertThrows(IllegalStateException.class,
                () -> factory.handle(bound.submitRequest(), chain));
        assertEquals("TASK_CREATE_RECORDED_PROVIDER_CONFLICT",
                incompleteIdentity.getMessage());

        DispatchTaskDTO drifted = DispatchTaskDTO.builder()
                .taskId("task-recorded-1")
                .providerType("wrong-provider")
                .build();
        when(taskDispatchFacade.getTask(
                "task-recorded-1", bound.submitRequest().getResolveContext()))
                .thenReturn(Optional.of(drifted));
        IllegalStateException drift = assertThrows(IllegalStateException.class,
                () -> factory.handle(bound.submitRequest(), chain));
        assertEquals("TASK_CREATE_RECORDED_PROVIDER_CONFLICT", drift.getMessage());
        verifyNoInteractions(chain);
    }

    @Test
    void receiptConflictStartedAndAmbiguousNeverFallThroughLegacy() throws Exception {
        BoundRequest bound = bindJwt(
                "jwt-state", false, TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        bound.submitRequest().setClientRequestId("550e8400-e29b-41d4-a716-446655440000");
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder().build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan(USER_ID, TENANT_ID);
        when(taskDispatchFacade.toTaskDispatchRequest(any())).thenReturn(dispatchRequest);
        when(taskDispatchFacade.resolveCreateExecutionPlan(any(), any())).thenReturn(plan);
        when(commandCoordinator.execute(any(), any(), any(), any(), any()))
                .thenThrow(
                        new IllegalStateException("TASK_CREATE_BINDING_CONFLICT"),
                        new IllegalStateException("TASK_CREATE_EFFECT_ALREADY_STARTED"),
                        new IllegalStateException("TASK_CREATE_EFFECT_AMBIGUOUS"));

        for (String code : List.of(
                "TASK_CREATE_BINDING_CONFLICT",
                "TASK_CREATE_EFFECT_ALREADY_STARTED",
                "TASK_CREATE_EFFECT_AMBIGUOUS")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> factory.handle(bound.submitRequest(), chain));
            assertEquals(code, failure.getMessage());
        }

        verify(commandCoordinator, times(3)).execute(any(), any(), any(), any(), any());
        verifyNoInteractions(chain);
        verify(taskDispatchFacade, never()).submitTaskDispatch(any());
    }

    @Test
    void nonOwnedSourcesAndCurrentUiAgentAskUseLegacyExactlyOnce() throws Exception {
        AtomicInteger terminalCalls = new AtomicInteger();
        AgentSubmitPipelineStage terminal = terminalStage(terminalCalls);
        DefaultAgentSubmitPipeline pipeline =
                new DefaultAgentSubmitPipeline(List.of(factory, terminal));

        BoundRequest openApi = bindJwt(
                "jwt-openapi", false, TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                "OPEN_API", USER_ID, TENANT_ID);
        assertFalse(factory.supports(openApi.submitRequest()));
        pipeline.submit(openApi.submitRequest());

        BoundRequest shared = bindJwt(
                "jwt-shared", false, TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                "SHARED_API", USER_ID, TENANT_ID);
        assertFalse(factory.supports(shared.submitRequest()));
        pipeline.submit(shared.submitRequest());

        BoundRequest unscopedForward = bindJwt(
                "jwt-forward", false,
                TrustedNavigatorTaskCreateCommandFactory.FORWARD_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_FORWARD_SOURCE,
                USER_ID, TENANT_ID);
        assertFalse(factory.supports(unscopedForward.submitRequest()));
        pipeline.submit(unscopedForward.submitRequest());

        BoundRequest transitionalAsk = bindJwt(
                "jwt-ask-ui", false, TrustedNavigatorTaskCreateCommandFactory.AGENT_ASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        assertFalse(factory.supports(transitionalAsk.submitRequest()));
        pipeline.submit(transitionalAsk.submitRequest());

        BoundRequest mixedTransitionalAsk = bindJwt(
                "jwt-ask-ui-mixed", false,
                TrustedNavigatorTaskCreateCommandFactory.AGENT_ASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        mixedTransitionalAsk.httpRequest().addHeader("X-API-Key", "foreign");
        assertFalse(factory.supports(mixedTransitionalAsk.submitRequest()));
        pipeline.submit(mixedTransitionalAsk.submitRequest());

        assertEquals(5, terminalCalls.get());
        assertNull(transitionalAsk.submitRequest().getClientRequestId());
        assertNull(mixedTransitionalAsk.submitRequest().getClientRequestId());
        verifyNoInteractions(taskDispatchFacade, commandCoordinator);
    }

    @Test
    void trustedApiKeyUiUsesDistinctLaneAndRecordedReplayNeverExposesRawKey()
            throws Exception {
        String requestId = "550e8400-e29b-41d4-a716-446655440001";
        BoundRequest freshRequest = bindApiKey(
                TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE);
        freshRequest.submitRequest().setClientRequestId(requestId);
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder().build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan(USER_ID, TENANT_ID);
        DispatchTaskDTO task = exactTask("task-api-key");
        A2aTask a2aTask = A2aTask.builder().id("task-api-key").build();
        when(taskDispatchFacade.toTaskDispatchRequest(any())).thenReturn(dispatchRequest);
        when(taskDispatchFacade.resolveCreateExecutionPlan(any(), any())).thenReturn(plan);
        when(commandCoordinator.execute(any(), any(), any(), any(), any()))
                .thenReturn(
                        new TaskCreateCommandCoordinator.Executed(
                                new TaskCreateCommandCoordinator.TaskReference("task-api-key"),
                                task),
                        new TaskCreateCommandCoordinator.RecordedReplay(
                                new TaskCreateCommandCoordinator.TaskReference("task-api-key")));
        when(taskDispatchFacade.getTask(eq("task-api-key"), any()))
                .thenReturn(Optional.of(task));
        when(taskDispatchFacade.toA2aTask(same(task))).thenReturn(a2aTask);

        AgentTaskSubmitResult fresh = factory.handle(freshRequest.submitRequest(), chain);

        BoundRequest replayRequest = bindApiKey(
                TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE);
        replayRequest.submitRequest().setClientRequestId(requestId);
        AgentTaskSubmitResult replay = factory.handle(replayRequest.submitRequest(), chain);

        assertTrue(factory.supports(replayRequest.submitRequest()));
        assertSame(task, fresh.getDispatchTask());
        assertSame(task, replay.getDispatchTask());
        ArgumentCaptor<CanonicalCommandEnvelope> envelopes =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator, times(2)).execute(
                same(dispatchRequest), any(AgentResolveContext.class), same(plan),
                envelopes.capture(), any());
        assertEquals(2, envelopes.getAllValues().size());
        for (CanonicalCommandEnvelope envelope : envelopes.getAllValues()) {
            assertEquals(CanonicalCommandEnvelope.CommandIngress.DIRECT,
                    envelope.binding().ingress().ingress());
            assertEquals(AuthorizationPrincipalType.NAVIGATOR_USER,
                    envelope.binding().actor().principalType());
            assertEquals(AuthorizationCredentialLane.NAVIGATOR_API_KEY,
                    envelope.binding().actor().lane());
            assertEquals(USER_ID, envelope.binding().ownership().ownerReference());
            assertEquals("navi.tenant.present.v1:" + TENANT_ID,
                    envelope.binding().ownership().tenantReference());
            assertFalse(envelope.toString().contains("api-key"));
            assertFalse(envelope.binding().actor().fingerprint().contains("api-key"));
        }
        assertEquals(envelopes.getAllValues().get(0).binding(),
                envelopes.getAllValues().get(1).binding());
        verify(taskDispatchFacade).getTask(eq("task-api-key"), any());
        verifyNoInteractions(chain);
    }

    @Test
    void trustedApiKeyA2aMintsCanonicalRequestAndKeepsA2aIngress() throws Exception {
        BoundRequest bound = bindApiKey(
                TrustedNavigatorTaskCreateCommandFactory.AGENT_ASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.A2A_SOURCE);
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder().build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan(USER_ID, TENANT_ID);
        DispatchTaskDTO task = exactTask("task-api-a2a");
        when(taskDispatchFacade.toTaskDispatchRequest(any())).thenReturn(dispatchRequest);
        when(taskDispatchFacade.resolveCreateExecutionPlan(any(), any())).thenReturn(plan);
        when(commandCoordinator.execute(any(), any(), any(), any(), any()))
                .thenReturn(new TaskCreateCommandCoordinator.Executed(
                        new TaskCreateCommandCoordinator.TaskReference("task-api-a2a"), task));
        when(taskDispatchFacade.toA2aTask(same(task)))
                .thenReturn(A2aTask.builder().id("task-api-a2a").build());

        factory.handle(bound.submitRequest(), chain);

        assertDoesNotThrow(() -> UUID.fromString(bound.submitRequest().getClientRequestId()));
        ArgumentCaptor<CanonicalCommandEnvelope> envelope =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator).execute(
                same(dispatchRequest), same(bound.submitRequest().getResolveContext()), same(plan),
                envelope.capture(), any());
        assertEquals(CanonicalCommandEnvelope.CommandIngress.A2A,
                envelope.getValue().binding().ingress().ingress());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_API_KEY,
                envelope.getValue().binding().actor().lane());
        verifyNoInteractions(chain);
    }

    @Test
    void invalidAndMixedApiKeysFailBeforePlanReceiptOrProvider() throws Exception {
        BoundRequest foreign = bindApiKey(
                TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE);
        foreign.httpRequest().addHeader("X-Task-Token", "foreign");
        assertThrows(SecurityException.class,
                () -> factory.handle(foreign.submitRequest(), chain));

        BoundRequest mixedQuery = bindApiKey(
                TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE);
        mixedQuery.httpRequest().addParameter("token", "foreign-query");
        assertThrows(SecurityException.class,
                () -> factory.handle(mixedQuery.submitRequest(), chain));

        BoundRequest mixedBearer = bindApiKey(
                TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE);
        mixedBearer.httpRequest().addHeader("Authorization", "Bearer foreign-jwt");
        assertThrows(SecurityException.class,
                () -> factory.handle(mixedBearer.submitRequest(), chain));

        BoundRequest invalid = bindInvalidApiKey(
                TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE);
        assertTrue(factory.supports(invalid.submitRequest()));
        assertThrows(SecurityException.class,
                () -> factory.handle(invalid.submitRequest(), chain));

        for (String invalidValue : List.of("", "   ")) {
            BoundRequest blank = bindInvalidApiKey(
                    TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                    TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE,
                    invalidValue);
            assertTrue(factory.supports(blank.submitRequest()));
            assertThrows(SecurityException.class,
                    () -> factory.handle(blank.submitRequest(), chain));
        }

        verifyNoInteractions(taskDispatchFacade, commandCoordinator, chain);
    }

    @Test
    void forwardScopedJwtFreshBindsSemanticReceiptAndParticipantsExactlyOnce()
            throws Exception {
        String requestId = "550e8400-e29b-41d4-a716-446655440010";
        String semanticFingerprint = "a".repeat(64);
        BoundRequest bound = bindJwt(
                "jwt-forward-fresh", false,
                TrustedNavigatorTaskCreateCommandFactory.FORWARD_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_FORWARD_SOURCE,
                USER_ID, TENANT_ID);
        TrustedNavigatorTaskCreateCommandFactory.ForwardCommandScope scope =
                factory.mintForwardScope(requestId, semanticFingerprint);
        bound.submitRequest().setClientRequestId(scope.clientRequestId());
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder().build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan(USER_ID, TENANT_ID);
        DispatchTaskDTO task = exactTask("task-forward-fresh");
        A2aTask a2aTask = A2aTask.builder().id("task-forward-fresh").build();
        List<String> participantOrder = new ArrayList<>();
        when(taskDispatchFacade.toTaskDispatchRequest(same(bound.submitRequest())))
                .thenReturn(dispatchRequest);
        when(taskDispatchFacade.resolveCreateExecutionPlan(
                same(dispatchRequest), same(bound.submitRequest().getResolveContext())))
                .thenReturn(plan);
        when(commandCoordinator.execute(
                same(dispatchRequest), same(bound.submitRequest().getResolveContext()),
                same(plan), any(), any(), any()))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.TaskCreateParticipants participants =
                            invocation.getArgument(5);
                    participants.prepareFreshTask();
                    participants.completeFreshTask(task);
                    return new TaskCreateCommandCoordinator.Executed(
                            new TaskCreateCommandCoordinator.TaskReference(task.getTaskId()),
                            task);
                });
        when(taskDispatchFacade.toA2aTask(same(task))).thenReturn(a2aTask);
        DefaultAgentSubmitPipeline pipeline =
                new DefaultAgentSubmitPipeline(List.of(factory));

        AgentTaskSubmitResult result = factory.executeForwardScoped(
                scope,
                bound.submitRequest(),
                new TrustedNavigatorTaskCreateCommandFactory.ForwardFreshParticipants() {
                    @Override
                    public void prepareFreshTask() {
                        participantOrder.add("prepare");
                    }

                    @Override
                    public void completeFreshTask(DispatchTaskDTO freshTask) {
                        assertSame(task, freshTask);
                        participantOrder.add("complete");
                    }
                },
                () -> pipeline.submit(bound.submitRequest()));

        assertSame(task, result.getDispatchTask());
        assertSame(a2aTask, result.getTask());
        assertEquals(List.of("prepare", "complete"), participantOrder);
        assertEquals("ForwardCommandScope[content-free]", scope.toString());
        ArgumentCaptor<CanonicalCommandEnvelope> envelope =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator).execute(
                same(dispatchRequest), same(bound.submitRequest().getResolveContext()),
                same(plan), envelope.capture(), any(), any());
        CanonicalCommandEnvelope.CommandBinding binding = envelope.getValue().binding();
        assertEquals(CanonicalCommandEnvelope.CommandIngress.DIRECT,
                binding.ingress().ingress());
        assertEquals(TrustedNavigatorTaskCreateCommandFactory.UI_FORWARD_SURFACE,
                binding.ingress().clientSurface());
        assertEquals(TrustedNavigatorTaskCreateCommandFactory.FORWARD_ROUTE,
                binding.ingress().routeId());
        assertEquals(requestId, binding.request().clientRequestId());
        assertEquals("UI_FORWARD_SHA256:" + semanticFingerprint,
                binding.request().idempotencyKey());
        assertEquals(requestId, binding.request().correlationId());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_JWT,
                binding.actor().lane());
        verifyNoInteractions(chain);
    }

    @Test
    void forwardScopedApiKeyReplayHydratesWithoutFreshParticipants() throws Exception {
        String requestId = "550e8400-e29b-41d4-a716-446655440011";
        BoundRequest bound = bindApiKey(
                TrustedNavigatorTaskCreateCommandFactory.FORWARD_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_FORWARD_SOURCE);
        TrustedNavigatorTaskCreateCommandFactory.ForwardCommandScope scope =
                factory.mintForwardScope(requestId, "b".repeat(64));
        bound.submitRequest().setClientRequestId(scope.clientRequestId());
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder().build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan(USER_ID, TENANT_ID);
        DispatchTaskDTO task = exactTask("task-forward-replay");
        when(taskDispatchFacade.toTaskDispatchRequest(any())).thenReturn(dispatchRequest);
        when(taskDispatchFacade.resolveCreateExecutionPlan(any(), any())).thenReturn(plan);
        when(commandCoordinator.execute(any(), any(), any(), any(), any(), any()))
                .thenReturn(new TaskCreateCommandCoordinator.RecordedReplay(
                        new TaskCreateCommandCoordinator.TaskReference(task.getTaskId())));
        when(taskDispatchFacade.getTask(eq(task.getTaskId()), any()))
                .thenReturn(Optional.of(task));
        when(taskDispatchFacade.toA2aTask(same(task)))
                .thenReturn(A2aTask.builder().id(task.getTaskId()).build());
        AtomicInteger participantCalls = new AtomicInteger();
        DefaultAgentSubmitPipeline pipeline =
                new DefaultAgentSubmitPipeline(List.of(factory));

        AgentTaskSubmitResult result = factory.executeForwardScoped(
                scope,
                bound.submitRequest(),
                countingForwardParticipants(participantCalls),
                () -> pipeline.submit(bound.submitRequest()));

        assertSame(task, result.getDispatchTask());
        assertEquals(0, participantCalls.get());
        ArgumentCaptor<CanonicalCommandEnvelope> envelope =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator).execute(
                same(dispatchRequest), same(bound.submitRequest().getResolveContext()),
                same(plan), envelope.capture(), any(), any());
        assertEquals(AuthorizationCredentialLane.NAVIGATOR_API_KEY,
                envelope.getValue().binding().actor().lane());
        verify(taskDispatchFacade).getTask(eq(task.getTaskId()),
                same(bound.submitRequest().getResolveContext()));
        verifyNoInteractions(chain);
    }

    @Test
    void forwardSemanticFingerprintChangesBindingForSameClientRequest() throws Exception {
        String requestId = "550e8400-e29b-41d4-a716-446655440012";
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder().build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan(USER_ID, TENANT_ID);
        DispatchTaskDTO task = exactTask("task-forward-binding");
        when(taskDispatchFacade.toTaskDispatchRequest(any())).thenReturn(dispatchRequest);
        when(taskDispatchFacade.resolveCreateExecutionPlan(any(), any())).thenReturn(plan);
        when(commandCoordinator.execute(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.TaskCreateParticipants participants =
                            invocation.getArgument(5);
                    participants.prepareFreshTask();
                    participants.completeFreshTask(task);
                    return new TaskCreateCommandCoordinator.Executed(
                            new TaskCreateCommandCoordinator.TaskReference(task.getTaskId()),
                            task);
                });
        when(taskDispatchFacade.toA2aTask(same(task)))
                .thenReturn(A2aTask.builder().id(task.getTaskId()).build());

        for (String fingerprint : List.of("c".repeat(64), "d".repeat(64))) {
            BoundRequest bound = bindJwt(
                    "jwt-forward-" + fingerprint.charAt(0), false,
                    TrustedNavigatorTaskCreateCommandFactory.FORWARD_ROUTE,
                    TrustedNavigatorTaskCreateCommandFactory.UI_FORWARD_SOURCE,
                    USER_ID, TENANT_ID);
            TrustedNavigatorTaskCreateCommandFactory.ForwardCommandScope scope =
                    factory.mintForwardScope(requestId, fingerprint);
            bound.submitRequest().setClientRequestId(scope.clientRequestId());
            DefaultAgentSubmitPipeline pipeline =
                    new DefaultAgentSubmitPipeline(List.of(factory));
            factory.executeForwardScoped(
                    scope,
                    bound.submitRequest(),
                    countingForwardParticipants(new AtomicInteger()),
                    () -> pipeline.submit(bound.submitRequest()));
        }

        ArgumentCaptor<CanonicalCommandEnvelope> envelopes =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        verify(commandCoordinator, times(2)).execute(
                same(dispatchRequest), any(), same(plan), envelopes.capture(), any(), any());
        CanonicalCommandEnvelope.CommandBinding first =
                envelopes.getAllValues().get(0).binding();
        CanonicalCommandEnvelope.CommandBinding second =
                envelopes.getAllValues().get(1).binding();
        assertEquals(first.request().clientRequestId(), second.request().clientRequestId());
        assertEquals(requestId, first.request().clientRequestId());
        assertEquals("UI_FORWARD_SHA256:" + "c".repeat(64),
                first.request().idempotencyKey());
        assertEquals("UI_FORWARD_SHA256:" + "d".repeat(64),
                second.request().idempotencyKey());
        assertNotEquals(first, second);
    }

    @Test
    void forwardScopeRejectsInvalidDigestMissingStageNestedAndReuse() {
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .resolveContext(AgentResolveContext.builder()
                        .requestSource(TrustedNavigatorTaskCreateCommandFactory.UI_FORWARD_SOURCE)
                        .build())
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> factory.mintForwardScope(null, "A".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> factory.mintForwardScope(null, "a".repeat(63)));

        TrustedNavigatorTaskCreateCommandFactory.ForwardCommandScope unused =
                factory.mintForwardScope(null, "e".repeat(64));
        assertThrows(IllegalStateException.class,
                () -> factory.executeForwardScoped(
                        unused, request, noOpForwardParticipants(), () -> "stage-skipped"));
        assertFalse(factory.supports(request));
        assertThrows(IllegalStateException.class,
                () -> factory.executeForwardScoped(
                        unused, request, noOpForwardParticipants(), () -> "reused"));

        TrustedNavigatorTaskCreateCommandFactory.ForwardCommandScope outer =
                factory.mintForwardScope(null, "f".repeat(64));
        TrustedNavigatorTaskCreateCommandFactory.ForwardCommandScope inner =
                factory.mintForwardScope(null, "1".repeat(64));
        assertThrows(IllegalStateException.class,
                () -> factory.executeForwardScoped(
                        outer,
                        request,
                        noOpForwardParticipants(),
                        () -> {
                            assertThrows(IllegalStateException.class,
                                    () -> factory.executeForwardScoped(
                                            inner,
                                            request,
                                            noOpForwardParticipants(),
                                            () -> "nested"));
                            return "nested-caught";
                        }));
        assertFalse(factory.supports(request));
        verifyNoInteractions(taskDispatchFacade, commandCoordinator, chain);
    }

    @Test
    void forwardScopeTamperingAndIncompleteFreshCallbacksFailClosed() throws Exception {
        DefaultAgentSubmitPipeline pipeline =
                new DefaultAgentSubmitPipeline(List.of(factory));

        BoundRequest routeDrift = bindJwt(
                "jwt-forward-route", false,
                TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_FORWARD_SOURCE,
                USER_ID, TENANT_ID);
        TrustedNavigatorTaskCreateCommandFactory.ForwardCommandScope routeScope =
                factory.mintForwardScope(null, "2".repeat(64));
        routeDrift.submitRequest().setClientRequestId(routeScope.clientRequestId());
        assertThrows(SecurityException.class,
                () -> factory.executeForwardScoped(
                        routeScope,
                        routeDrift.submitRequest(),
                        noOpForwardParticipants(),
                        () -> pipeline.submit(routeDrift.submitRequest())));
        assertFalse(factory.supports(routeDrift.submitRequest()));

        BoundRequest identity = bindJwt(
                "jwt-forward-identity", false,
                TrustedNavigatorTaskCreateCommandFactory.FORWARD_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_FORWARD_SOURCE,
                USER_ID, TENANT_ID);
        TrustedNavigatorTaskCreateCommandFactory.ForwardCommandScope identityScope =
                factory.mintForwardScope(null, "3".repeat(64));
        identity.submitRequest().setClientRequestId(identityScope.clientRequestId());
        AgentTaskSubmitRequest replacement = AgentTaskSubmitRequest.builder()
                .clientRequestId(identityScope.clientRequestId())
                .resolveContext(identity.submitRequest().getResolveContext())
                .build();
        assertThrows(IllegalStateException.class,
                () -> factory.executeForwardScoped(
                        identityScope,
                        identity.submitRequest(),
                        noOpForwardParticipants(),
                        () -> pipeline.submit(replacement)));
        assertFalse(factory.supports(identity.submitRequest()));

        BoundRequest incomplete = bindJwt(
                "jwt-forward-incomplete", false,
                TrustedNavigatorTaskCreateCommandFactory.FORWARD_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_FORWARD_SOURCE,
                USER_ID, TENANT_ID);
        TrustedNavigatorTaskCreateCommandFactory.ForwardCommandScope incompleteScope =
                factory.mintForwardScope(null, "4".repeat(64));
        incomplete.submitRequest().setClientRequestId(incompleteScope.clientRequestId());
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder().build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan(USER_ID, TENANT_ID);
        DispatchTaskDTO task = exactTask("task-forward-incomplete");
        when(taskDispatchFacade.toTaskDispatchRequest(same(incomplete.submitRequest())))
                .thenReturn(dispatchRequest);
        when(taskDispatchFacade.resolveCreateExecutionPlan(
                same(dispatchRequest), same(incomplete.submitRequest().getResolveContext())))
                .thenReturn(plan);
        when(commandCoordinator.execute(any(), any(), any(), any(), any(), any()))
                .thenReturn(new TaskCreateCommandCoordinator.Executed(
                        new TaskCreateCommandCoordinator.TaskReference(task.getTaskId()), task));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> factory.executeForwardScoped(
                        incompleteScope,
                        incomplete.submitRequest(),
                        noOpForwardParticipants(),
                        () -> pipeline.submit(incomplete.submitRequest())));
        assertEquals("FORWARD_TASK_CREATE_FRESH_PARTICIPANTS_INCOMPLETE",
                failure.getMessage());
        assertFalse(factory.supports(incomplete.submitRequest()));
        verifyNoInteractions(chain);
    }

    @Test
    void jwtMixedCredentialRouteAttributeAndContextDriftFailBeforeResolution() throws Exception {
        for (String foreignHeader : List.of(
                "X-API-Key",
                "X-Navigator-API-Key",
                "X-Task-Token",
                "X-Worker-Token",
                "X-Platform-Admin-Key",
                "X-System-Admin-Key",
                "X-Operator-Token",
                "X-Principal-Token",
                "X-TMS-Agent-Token")) {
            BoundRequest mixed = bindJwt(
                    "jwt-mixed", false,
                    TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                    TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE,
                    USER_ID, TENANT_ID);
            mixed.httpRequest().addHeader(foreignHeader, "foreign");
            assertThrows(SecurityException.class,
                    () -> factory.handle(mixed.submitRequest(), chain), foreignHeader);
        }

        BoundRequest attributeDrift = bindJwt(
                "jwt-attr", false, TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        attributeDrift.httpRequest().setAttribute("userId", "other-user");
        assertThrows(SecurityException.class,
                () -> factory.handle(attributeDrift.submitRequest(), chain));

        BoundRequest contextDrift = bindJwt(
                "jwt-context", false, TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        contextDrift.submitRequest().getResolveContext().setTenantId("other-tenant");
        assertThrows(SecurityException.class,
                () -> factory.handle(contextDrift.submitRequest(), chain));

        BoundRequest methodDrift = bindJwt(
                "jwt-method", false, TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        methodDrift.httpRequest().setMethod("GET");
        assertThrows(SecurityException.class,
                () -> factory.handle(methodDrift.submitRequest(), chain));

        BoundRequest routeDrift = bindJwt(
                "jwt-route", false, "/api/v1/tasks/{taskId}",
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        assertThrows(SecurityException.class,
                () -> factory.handle(routeDrift.submitRequest(), chain));

        BoundRequest queryMixed = bindJwt(
                "jwt-query-mixed", true, TrustedNavigatorTaskCreateCommandFactory.AGENT_ASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.A2A_SOURCE, USER_ID, TENANT_ID);
        queryMixed.httpRequest().addHeader("Authorization", "Basic foreign");
        assertThrows(SecurityException.class,
                () -> factory.handle(queryMixed.submitRequest(), chain));

        verifyNoInteractions(taskDispatchFacade, commandCoordinator);
        verifyNoInteractions(chain);
    }

    @Test
    void planOwnerOrTenantDriftFailsBeforeReceipt() throws Exception {
        BoundRequest bound = bindJwt(
                "jwt-owner", false, TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        bound.submitRequest().setClientRequestId("550e8400-e29b-41d4-a716-446655440000");
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder().build();
        TaskCreateTargetResolver.CreateExecutionPlan foreignPlan =
                plan("other-user", TENANT_ID);
        when(taskDispatchFacade.toTaskDispatchRequest(any())).thenReturn(dispatchRequest);
        when(taskDispatchFacade.resolveCreateExecutionPlan(any(), any()))
                .thenReturn(foreignPlan);

        SecurityException failure = assertThrows(SecurityException.class,
                () -> factory.handle(bound.submitRequest(), chain));

        assertEquals("TRUSTED_NAVIGATOR_PLAN_OWNER_CONFLICT", failure.getMessage());
        verify(taskDispatchFacade).resolveCreateExecutionPlan(
                same(dispatchRequest), same(bound.submitRequest().getResolveContext()));
        verifyNoInteractions(commandCoordinator, chain);
    }

    @Test
    void invalidUuidFailsPreResolutionAndFacadeConversionNeverProjectsRequestId() throws Exception {
        BoundRequest bound = bindJwt(
                "jwt-invalid-id", false, TrustedNavigatorTaskCreateCommandFactory.TASK_ROUTE,
                TrustedNavigatorTaskCreateCommandFactory.UI_SOURCE, USER_ID, TENANT_ID);
        bound.submitRequest().setClientRequestId("1-1-1-1-1");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> factory.handle(bound.submitRequest(), chain));

        assertEquals("clientRequestId must be a canonical UUID", failure.getMessage());
        verifyNoInteractions(taskDispatchFacade, commandCoordinator, chain);

        TaskDispatchFacade conversionFacade = new TaskDispatchFacade(
                mock(UnifiedAgentResolver.class),
                mock(SessionBindingService.class),
                mock(SessionRepository.class),
                mock(SessionTaskResourceAccessService.class),
                List.of(), List.of(), List.of(), List.of(),
                mock(LlmModelManager.class));
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        AgentTaskSubmitRequest conversionRequest = AgentTaskSubmitRequest.builder()
                .clientRequestId(requestId)
                .prompt("visible content")
                .metadata(Map.of("safe", "value"))
                .build();

        TaskDispatchRequest converted =
                conversionFacade.toTaskDispatchRequest(conversionRequest);

        assertEquals("visible content", converted.getPrompt());
        assertEquals("value", converted.getMetadata().get("safe"));
        assertFalse(converted.getMetadata().containsKey("clientRequestId"));
        assertFalse(converted.getMetadata().containsValue(requestId));
    }

    private TaskCreateTargetResolver.CreateExecutionPlan plan(
            String ownerUserId,
            String tenantId) {
        TaskCreateTargetResolver.CreateExecutionPlan plan =
                mock(TaskCreateTargetResolver.CreateExecutionPlan.class);
        lenient().when(plan.executionRoute())
                .thenReturn(TaskCreateTargetResolver.ExecutionRoute.A2A);
        lenient().when(plan.ownerUserId()).thenReturn(ownerUserId);
        lenient().when(plan.tenantId()).thenReturn(tenantId);
        lenient().when(plan.logicalAgentId()).thenReturn("agent-1");
        lenient().when(plan.providerType()).thenReturn("claude-worker");
        lenient().when(plan.physicalWorkerId()).thenReturn("worker-1");
        lenient().when(plan.modelConfigId()).thenReturn("model-config-1");
        lenient().when(plan.model()).thenReturn("claude-sonnet");
        lenient().when(plan.sessionId()).thenReturn("session-1");
        lenient().when(plan.directoryId()).thenReturn("directory-1");
        return plan;
    }

    private DispatchTaskDTO exactTask(String taskId) {
        return DispatchTaskDTO.builder()
                .taskId(taskId)
                .agentId("agent-1")
                .providerType("claude-worker")
                .workerId("worker-1")
                .modelConfigId("model-config-1")
                .model("claude-sonnet")
                .sessionId("session-1")
                .directoryId("directory-1")
                .status("PENDING")
                .build();
    }

    private BoundRequest bindJwt(
            String token,
            boolean queryToken,
            String route,
            String source,
            String userId,
            String tenantId) throws Exception {
        resetBoundContext();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", concreteUri(route));
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
        if (queryToken) {
            request.addParameter("token", token);
        } else {
            request.addHeader("Authorization", "Bearer " + token);
        }
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(token)).thenReturn(userId);
        when(jwtUtil.getUsernameFromToken(token)).thenReturn(USERNAME);
        when(jwtUtil.getTenantIdFromToken(token)).thenReturn(tenantId);
        when(jwtUtil.getRolesFromToken(token)).thenReturn(ROLES);
        if (!queryToken) {
            when(jwtUtil.needsRenewal(token)).thenReturn(false);
        }
        AuthInterceptor interceptor = new AuthInterceptor(jwtUtil, userAuthService);
        assertTrue(interceptor.preHandle(request, response, new Object()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .requestSource(source)
                .build();
        AgentTaskSubmitRequest submitRequest = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .resolveContext(context)
                .build();
        return new BoundRequest(submitRequest, request);
    }

    private BoundRequest bindApiKey(String route, String source) throws Exception {
        resetBoundContext();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", concreteUri(route));
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
        request.addHeader("X-API-Key", "api-key");
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setTenantId(TENANT_ID);
        user.setRoles(ROLES);
        when(userAuthService.getUserByApiKey("api-key")).thenReturn(Optional.of(user));
        AuthInterceptor interceptor = new AuthInterceptor(jwtUtil, userAuthService);
        assertTrue(interceptor.preHandle(request, response, new Object()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        return new BoundRequest(
                AgentTaskSubmitRequest.builder()
                        .agentId("agent-1")
                        .resolveContext(AgentResolveContext.builder()
                                .userId(USER_ID)
                                .tenantId(TENANT_ID)
                                .requestSource(source)
                                .build())
                        .build(),
                request);
    }

    private BoundRequest bindInvalidApiKey(String route, String source) throws Exception {
        return bindInvalidApiKey(route, source, "revoked-api-key");
    }

    private BoundRequest bindInvalidApiKey(
            String route,
            String source,
            String apiKey) throws Exception {
        resetBoundContext();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", concreteUri(route));
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
        request.addHeader("X-API-Key", apiKey);
        if (!apiKey.isEmpty()) {
            when(userAuthService.getUserByApiKey(apiKey)).thenReturn(Optional.empty());
        }
        AuthInterceptor interceptor = new AuthInterceptor(jwtUtil, userAuthService);
        assertTrue(interceptor.preHandle(request, response, new Object()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        return new BoundRequest(
                AgentTaskSubmitRequest.builder()
                        .agentId("agent-1")
                        .resolveContext(AgentResolveContext.builder()
                                .userId(USER_ID)
                                .tenantId(TENANT_ID)
                                .requestSource(source)
                                .build())
                        .build(),
                request);
    }

    private TrustedNavigatorTaskCreateCommandFactory.ForwardFreshParticipants
            noOpForwardParticipants() {
        return countingForwardParticipants(new AtomicInteger());
    }

    private TrustedNavigatorTaskCreateCommandFactory.ForwardFreshParticipants
            countingForwardParticipants(AtomicInteger calls) {
        return new TrustedNavigatorTaskCreateCommandFactory.ForwardFreshParticipants() {
            @Override
            public void prepareFreshTask() {
                calls.incrementAndGet();
            }

            @Override
            public void completeFreshTask(DispatchTaskDTO freshTask) {
                calls.incrementAndGet();
            }
        };
    }

    private AgentSubmitPipelineStage terminalStage(AtomicInteger calls) {
        return new AgentSubmitPipelineStage() {
            @Override
            public String name() {
                return "test-terminal";
            }

            @Override
            public int order() {
                return Integer.MAX_VALUE;
            }

            @Override
            public AgentTaskSubmitResult handle(
                    AgentTaskSubmitRequest request,
                    AgentSubmitPipelineChain chain) {
                calls.incrementAndGet();
                return AgentTaskSubmitResult.of(
                        A2aTask.builder().id("legacy-task").build());
            }
        };
    }

    private void resetBoundContext() {
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private static String concreteUri(String route) {
        return route.replace("{agentId}", "agent-1")
                .replace("{taskId}", "task-1");
    }

    private record BoundRequest(
            AgentTaskSubmitRequest submitRequest,
            MockHttpServletRequest httpRequest) {
    }
}
