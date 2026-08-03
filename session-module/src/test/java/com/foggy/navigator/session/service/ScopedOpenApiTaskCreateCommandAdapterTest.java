package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipelineChain;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipelineStage;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.agent.pipeline.DefaultAgentSubmitPipeline;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScopedOpenApiTaskCreateCommandAdapterTest {

    private static final String REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private TaskDispatchFacade taskDispatchFacade;
    @Mock
    private TaskCreateCommandCoordinator commandCoordinator;
    @Mock
    private AgentSubmitPipelineChain chain;

    private VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private ScopedOpenApiTaskCreateCommandAdapter adapter;

    @BeforeEach
    void setUp() {
        serverAuthority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                "test.policy.v1",
                Clock.fixed(Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(5));
        adapter = new ScopedOpenApiTaskCreateCommandAdapter(
                taskDispatchFacade, commandCoordinator, serverAuthority);
    }

    @Test
    void scopedFreshBuildsSafeBindingAndRunsParticipantsExactlyOnce() {
        Bound bound = bindCanonicalRequest();
        DispatchTaskDTO fresh = exactTask("task-fresh");
        A2aTask a2aTask = A2aTask.builder().id("task-fresh").build();
        List<String> order = new ArrayList<>();
        ScopedOpenApiTaskCreateCommandAdapter.FreshParticipants participants =
                participants(order);
        when(commandCoordinator.execute(
                same(bound.dispatchRequest), same(bound.context), same(bound.plan),
                any(), any(), any()))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.TaskCreateParticipants hooks =
                            invocation.getArgument(5);
                    hooks.prepareFreshTask();
                    order.add("provider");
                    hooks.completeFreshTask(fresh);
                    return new TaskCreateCommandCoordinator.Executed(
                            new TaskCreateCommandCoordinator.TaskReference("task-fresh"), fresh);
                });
        when(taskDispatchFacade.toA2aTask(same(fresh))).thenReturn(a2aTask);

        AgentTaskSubmitResult result = adapter.executeScoped(
                scope("runtime-access-id-one", "credential-1"),
                bound.submitRequest,
                participants,
                () -> {
                    assertTrue(adapter.supports(bound.submitRequest));
                    return adapter.handle(bound.submitRequest, chain);
                });

        assertSame(fresh, result.getDispatchTask());
        assertSame(a2aTask, result.getTask());
        assertEquals(List.of("prepare", "provider", "complete"), order);
        assertEquals(Integer.MAX_VALUE - 2, adapter.order());
        assertEquals("scoped-openapi-task-create-command", adapter.name());
        assertFalse(adapter.supports(bound.submitRequest));
        verifyNoInteractions(chain);

        ArgumentCaptor<CanonicalCommandEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        ArgumentCaptor<VerifiedCommandAuthorizationDecision> decisionCaptor =
                ArgumentCaptor.forClass(VerifiedCommandAuthorizationDecision.class);
        verify(commandCoordinator).execute(
                same(bound.dispatchRequest), same(bound.context), same(bound.plan),
                envelopeCaptor.capture(), decisionCaptor.capture(), any());
        CanonicalCommandEnvelope envelope = envelopeCaptor.getValue();
        CanonicalCommandEnvelope.CommandBinding binding = envelope.binding();
        assertEquals(CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                binding.ingress().ingress());
        assertEquals("NAVIGATOR_OPEN_API", binding.ingress().clientSurface());
        assertEquals("/api/v1/open/agents/{agentId}/ask", binding.ingress().routeId());
        assertEquals(REQUEST_ID, binding.request().clientRequestId());
        assertEquals(CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                binding.actor().kind());
        assertEquals(AuthorizationPrincipalType.CLIENT_APP,
                binding.actor().principalType());
        assertEquals(AuthorizationCredentialLane.CLIENT_APP_RUNTIME_ACCESS,
                binding.actor().lane());
        assertEquals(64, binding.actor().fingerprint().length());
        assertEquals("owner-1", binding.ownership().ownerReference());
        assertEquals("app-1", binding.ownership().clientAppReference());
        assertTrue(binding.ownership().upstreamReference()
                .startsWith("OPENAPI_UPSTREAM_SHA256:"));
        assertEquals(binding, serverAuthority.requireVerified(
                envelope, decisionCaptor.getValue()));

        String serializedEvidence = envelope.toString();
        assertFalse(serializedEvidence.contains("runtime-access-id-one"));
        assertFalse(serializedEvidence.contains("prompt-secret"));
        assertFalse(serializedEvidence.contains("plain-task-token"));
        assertFalse(serializedEvidence.contains("attachment-secret"));
        assertEquals("OpenApiCommandScope[content-free]",
                scope("another-runtime-id", "credential-safe").toString());
    }

    @Test
    void recordedReplayHydratesExactTaskWithZeroParticipantsAndRejectsDrift() {
        Bound bound = bindCanonicalRequest();
        DispatchTaskDTO recorded = exactTask("task-recorded");
        A2aTask a2aTask = A2aTask.builder().id("task-recorded").build();
        AtomicInteger participantCalls = new AtomicInteger();
        when(commandCoordinator.execute(any(), any(), any(), any(), any(), any()))
                .thenReturn(new TaskCreateCommandCoordinator.RecordedReplay(
                        new TaskCreateCommandCoordinator.TaskReference("task-recorded")));
        when(taskDispatchFacade.getTask("task-recorded", bound.context))
                .thenReturn(Optional.of(recorded));
        when(taskDispatchFacade.toA2aTask(same(recorded))).thenReturn(a2aTask);

        AgentTaskSubmitResult replay = adapter.executeScoped(
                scope("runtime-access-id-one", "credential-1"),
                bound.submitRequest,
                countingParticipants(participantCalls),
                () -> adapter.handle(bound.submitRequest, chain));

        assertSame(recorded, replay.getDispatchTask());
        assertEquals(0, participantCalls.get());
        verifyNoInteractions(chain);

        Bound driftBound = bindCanonicalRequest();
        DispatchTaskDTO drifted = exactTask("task-recorded");
        drifted.setWorkerId("worker-drifted");
        when(taskDispatchFacade.getTask("task-recorded", driftBound.context))
                .thenReturn(Optional.of(drifted));
        AtomicInteger driftCalls = new AtomicInteger();

        IllegalStateException drift = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        scope("runtime-access-id-two", "credential-1"),
                        driftBound.submitRequest,
                        countingParticipants(driftCalls),
                        () -> adapter.handle(driftBound.submitRequest, chain)));

        assertEquals("OPENAPI_TASK_CREATE_RECORDED_WORKER_CONFLICT", drift.getMessage());
        assertEquals(0, driftCalls.get());
        assertFalse(adapter.supports(driftBound.submitRequest));
    }

    @Test
    void coordinatorConflictHasZeroParticipantsNoFallbackAndFinallyClears() {
        Bound bound = bindCanonicalRequest();
        AtomicInteger calls = new AtomicInteger();
        when(commandCoordinator.execute(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("TASK_CREATE_EFFECT_ALREADY_STARTED"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        scope("runtime-access-id", "credential-1"),
                        bound.submitRequest,
                        countingParticipants(calls),
                        () -> adapter.handle(bound.submitRequest, chain)));

        assertEquals("TASK_CREATE_EFFECT_ALREADY_STARTED", failure.getMessage());
        assertEquals(0, calls.get());
        verifyNoInteractions(chain);
        assertFalse(adapter.supports(bound.submitRequest));
    }

    @Test
    void scopeMismatchAndNestedScopePoisonFailClosedWithoutCoordinatorOrFallback() {
        Bound mismatch = bindCanonicalRequest();
        mismatch.submitRequest.getResolveContext().setRequestSource("A2A");
        AtomicInteger calls = new AtomicInteger();

        IllegalStateException sourceFailure = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        scope("runtime-access-id", "credential-1"),
                        mismatch.submitRequest,
                        countingParticipants(calls),
                        () -> adapter.handle(mismatch.submitRequest, chain)));
        assertEquals("OPENAPI_TASK_CREATE_SCOPE_AUTHORITY_CONFLICT",
                sourceFailure.getMessage());
        assertEquals(0, calls.get());
        verifyNoInteractions(commandCoordinator, chain);

        Bound outer = bindCanonicalRequest();
        Bound inner = bindCanonicalRequest();
        IllegalStateException poisoned = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        scope("runtime-access-outer", "credential-1"),
                        outer.submitRequest,
                        countingParticipants(calls),
                        () -> {
                            IllegalStateException nested = assertThrows(
                                    IllegalStateException.class,
                                    () -> adapter.executeScoped(
                                            scope("runtime-access-inner", "credential-1"),
                                            inner.submitRequest,
                                            countingParticipants(calls),
                                            () -> "must-not-run"));
                            assertEquals("OPENAPI_TASK_CREATE_SCOPE_NESTED", nested.getMessage());
                            return AgentTaskSubmitResult.of(A2aTask.builder().id("forged").build());
                        }));
        assertEquals("OPENAPI_TASK_CREATE_SCOPE_POISONED", poisoned.getMessage());
        assertFalse(adapter.supports(outer.submitRequest));
        verifyNoMoreInteractions(commandCoordinator, chain);
    }

    @Test
    void exactRequestObjectTargetAndSingleUseAreEnforced() {
        Bound bound = bindCanonicalRequest();
        AgentTaskSubmitRequest differentObject = canonicalSubmitRequest();
        AtomicInteger calls = new AtomicInteger();
        ScopedOpenApiTaskCreateCommandAdapter.OpenApiCommandScope objectScope =
                scope("runtime-access-id", "credential-1");

        IllegalStateException objectFailure = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        objectScope,
                        bound.submitRequest,
                        countingParticipants(calls),
                        () -> adapter.handle(differentObject, chain)));
        assertEquals("OPENAPI_TASK_CREATE_SCOPE_REQUEST_CONFLICT", objectFailure.getMessage());

        IllegalStateException reused = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        objectScope,
                        bound.submitRequest,
                        countingParticipants(calls),
                        () -> "must-not-run"));
        assertEquals("OPENAPI_TASK_CREATE_SCOPE_ALREADY_USED", reused.getMessage());

        Bound target = bindCanonicalRequest();
        target.submitRequest.setWorkerId("worker-drifted");
        IllegalStateException targetFailure = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        scope("runtime-access-id-two", "credential-1"),
                        target.submitRequest,
                        countingParticipants(calls),
                        () -> adapter.handle(target.submitRequest, chain)));
        assertEquals("OPENAPI_TASK_CREATE_SCOPE_TARGET_CONFLICT", targetFailure.getMessage());
        assertEquals(0, calls.get());
        verifyNoInteractions(commandCoordinator, chain);
    }

    @Test
    void accessTokenRotationKeepsStableBindingAndCredentialChangeChangesFingerprint() {
        List<CanonicalCommandEnvelope.CommandBinding> bindings = new ArrayList<>();
        when(commandCoordinator.execute(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    bindings.add(((CanonicalCommandEnvelope) invocation.getArgument(3)).binding());
                    TaskCreateCommandCoordinator.TaskCreateParticipants hooks =
                            invocation.getArgument(5);
                    DispatchTaskDTO fresh = exactTask("task-" + bindings.size());
                    hooks.prepareFreshTask();
                    hooks.completeFreshTask(fresh);
                    return new TaskCreateCommandCoordinator.Executed(
                            new TaskCreateCommandCoordinator.TaskReference(fresh.getTaskId()), fresh);
                });
        when(taskDispatchFacade.toA2aTask(any()))
                .thenAnswer(invocation -> A2aTask.builder()
                        .id(invocation.<DispatchTaskDTO>getArgument(0).getTaskId())
                        .build());

        runFreshScope("runtime-access-one", "credential-1", "upstream-system-1");
        runFreshScope("runtime-access-two", "credential-1", "upstream-system-1");
        runFreshScope("runtime-access-three", "credential-2", "upstream-system-1");
        runFreshScope("runtime-access-four", "credential-1", "upstream-system-2");

        assertEquals(bindings.get(0), bindings.get(1));
        assertNotEquals(bindings.get(0).actor().fingerprint(),
                bindings.get(2).actor().fingerprint());
        assertEquals(bindings.get(0).target(), bindings.get(2).target());
        assertEquals(bindings.get(0).effect(), bindings.get(2).effect());
        assertNotEquals(bindings.get(0).ownership().upstreamReference(),
                bindings.get(3).ownership().upstreamReference());
        assertEquals(bindings.get(0).actor().fingerprint(),
                bindings.get(3).actor().fingerprint());
    }

    @Test
    void unscopedOpenApiAndOtherSourcesContinueToLegacyTerminalExactlyOnce() {
        AgentSubmitPipelineStage legacy = mock(AgentSubmitPipelineStage.class);
        AgentTaskSubmitResult legacyResult = AgentTaskSubmitResult.of(
                A2aTask.builder().id("legacy-task").build());
        when(legacy.name()).thenReturn("legacy-terminal");
        when(legacy.order()).thenReturn(Integer.MAX_VALUE);
        when(legacy.supports(any())).thenReturn(true);
        when(legacy.handle(any(), any())).thenReturn(legacyResult);
        DefaultAgentSubmitPipeline pipeline = new DefaultAgentSubmitPipeline(
                List.of(adapter, legacy));

        AgentTaskSubmitRequest openApi = canonicalSubmitRequest();
        AgentTaskSubmitRequest system = canonicalSubmitRequest();
        system.getResolveContext().setRequestSource("SYSTEM");

        assertSame(legacyResult, pipeline.submit(openApi));
        assertSame(legacyResult, pipeline.submit(system));
        verify(legacy, times(2)).handle(any(), any());
        verifyNoInteractions(commandCoordinator);
    }

    private void runFreshScope(
            String runtimeAccessEvidence,
            String credentialId,
            String upstreamSystemId) {
        Bound bound = bindCanonicalRequest();
        adapter.executeScoped(
                scope(runtimeAccessEvidence, credentialId, upstreamSystemId),
                bound.submitRequest,
                participants(new ArrayList<>()),
                () -> adapter.handle(bound.submitRequest, chain));
    }

    private Bound bindCanonicalRequest() {
        AgentTaskSubmitRequest submitRequest = canonicalSubmitRequest();
        AgentResolveContext context = submitRequest.getResolveContext();
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder()
                .agentId("agent-1")
                .providerType("claude-worker")
                .workerId("worker-1")
                .prompt("prompt-secret")
                .directoryId("directory-1")
                .modelConfigId("model-config-1")
                .model("claude-sonnet")
                .contextId("context-1")
                .metadata(Map.of("task_scoped_token", "plain-task-token"))
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan();
        lenient().when(taskDispatchFacade.toTaskDispatchRequest(same(submitRequest)))
                .thenReturn(dispatchRequest);
        lenient().when(taskDispatchFacade.resolveCreateExecutionPlan(
                        same(dispatchRequest), same(context)))
                .thenReturn(plan);
        return new Bound(submitRequest, context, dispatchRequest, plan);
    }

    private AgentTaskSubmitRequest canonicalSubmitRequest() {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("owner-1")
                .tenantId("tenant-1")
                .modelConfigId("model-config-1")
                .requestSource("OPEN_API")
                .build();
        return AgentTaskSubmitRequest.builder()
                .clientRequestId(REQUEST_ID)
                .agentId("agent-1")
                .providerType("claude-worker")
                .workerId("worker-1")
                .prompt("prompt-secret")
                .directoryId("directory-1")
                .modelConfigId("model-config-1")
                .model("claude-sonnet")
                .contextId("context-1")
                .attachments(List.of(Map.of("name", "attachment-secret")))
                .metadata(Map.of("task_scoped_token", "plain-task-token"))
                .resolveContext(context)
                .build();
    }

    private TaskCreateTargetResolver.CreateExecutionPlan plan() {
        TaskCreateTargetResolver.CreateExecutionPlan plan =
                mock(TaskCreateTargetResolver.CreateExecutionPlan.class);
        lenient().when(plan.executionRoute())
                .thenReturn(TaskCreateTargetResolver.ExecutionRoute.A2A);
        lenient().when(plan.ownerUserId()).thenReturn("owner-1");
        lenient().when(plan.tenantId()).thenReturn("tenant-1");
        lenient().when(plan.logicalAgentId()).thenReturn("agent-1");
        lenient().when(plan.providerType()).thenReturn("claude-worker");
        lenient().when(plan.physicalWorkerId()).thenReturn("worker-1");
        lenient().when(plan.modelConfigId()).thenReturn("model-config-1");
        lenient().when(plan.model()).thenReturn("claude-sonnet");
        lenient().when(plan.sessionId()).thenReturn(null);
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
                .sessionId(null)
                .directoryId("directory-1")
                .status("PENDING")
                .build();
    }

    private ScopedOpenApiTaskCreateCommandAdapter.OpenApiCommandScope scope(
            String runtimeAccessEvidence,
            String credentialId) {
        return scope(runtimeAccessEvidence, credentialId, "upstream-system-1");
    }

    private ScopedOpenApiTaskCreateCommandAdapter.OpenApiCommandScope scope(
            String runtimeAccessEvidence,
            String credentialId,
            String upstreamSystemId) {
        return ScopedOpenApiTaskCreateCommandAdapter.OpenApiCommandScope.authenticated(
                REQUEST_ID,
                "tenant-1",
                "owner-1",
                "app-1",
                upstreamSystemId,
                "upstream-user-1",
                credentialId,
                runtimeAccessEvidence,
                new ScopedOpenApiTaskCreateCommandAdapter.TargetExpectation(
                        "agent-1",
                        "context-1",
                        "claude-worker",
                        "worker-1",
                        "model-config-1",
                        "claude-sonnet",
                        "directory-1"));
    }

    private ScopedOpenApiTaskCreateCommandAdapter.FreshParticipants participants(
            List<String> order) {
        return new ScopedOpenApiTaskCreateCommandAdapter.FreshParticipants() {
            @Override
            public void prepare(TaskDispatchRequest canonicalRequest) {
                order.add("prepare");
            }

            @Override
            public void complete(
                    TaskDispatchRequest canonicalRequest,
                    DispatchTaskDTO freshTask) {
                order.add("complete");
            }
        };
    }

    private ScopedOpenApiTaskCreateCommandAdapter.FreshParticipants countingParticipants(
            AtomicInteger calls) {
        return new ScopedOpenApiTaskCreateCommandAdapter.FreshParticipants() {
            @Override
            public void prepare(TaskDispatchRequest canonicalRequest) {
                calls.incrementAndGet();
            }

            @Override
            public void complete(
                    TaskDispatchRequest canonicalRequest,
                    DispatchTaskDTO freshTask) {
                calls.incrementAndGet();
            }
        };
    }

    private record Bound(
            AgentTaskSubmitRequest submitRequest,
            AgentResolveContext context,
            TaskDispatchRequest dispatchRequest,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
    }
}
