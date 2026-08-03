package com.foggy.navigator.session.service;

import com.foggy.navigator.auth.repository.UserRepository;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.common.entity.UserEntity;
import com.foggy.navigator.session.agent.pipeline.DefaultAgentSubmitPipeline;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipelineChain;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.agent.pipeline.TaskDispatchSubmitPipelineStage;
import com.foggy.navigator.session.command.CommandOnceReceiptService;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SharingKeyRepository;
import com.foggy.navigator.session.util.SharingKeyGenerator;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScopedSharedTaskCreateCommandAdapterTest {

    private static final String REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");

    @Mock private TaskDispatchFacade taskDispatchFacade;
    @Mock private TaskCreateCommandCoordinator commandCoordinator;
    @Mock private AgentSubmitPipelineChain chain;
    @Mock private CommandOnceReceiptService receiptService;
    @Mock private SharingKeyRepository sharingKeyRepository;
    @Mock private SharingKeyGenerator keyGenerator;
    @Mock private UnifiedAgentResolver agentResolver;
    @Mock private UserRepository userRepository;

    private SharingKeyService sharingKeyService;
    private VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private ScopedSharedTaskCreateCommandAdapter adapter;

    @BeforeEach
    void setUp() {
        sharingKeyService = new SharingKeyService(
                sharingKeyRepository,
                keyGenerator,
                agentResolver,
                userRepository,
                "http://localhost:8112");
        serverAuthority = new VerifiedCommandAuthorizationDecision.ServerAuthority(
                "test.policy.v1",
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));
        adapter = new ScopedSharedTaskCreateCommandAdapter(
                taskDispatchFacade,
                commandCoordinator,
                serverAuthority,
                sharingKeyService);
    }

    @Test
    void scopeMintingIsAuthorityBackedCanonicalSingleUseNonNestedAndAlwaysCleared() {
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope = mintScope(null);

        assertEquals("key-1", scope.sharingKeyId());
        assertEquals("owner-1", scope.ownerUserId());
        assertEquals("tenant-1", scope.tenantId());
        assertEquals("agent-1", scope.agentId());
        assertEquals(scope.clientRequestId(), UUID.fromString(scope.clientRequestId()).toString());
        AgentResolveContext context = scope.newResolveContext();
        assertEquals("owner-1", context.getUserId());
        assertEquals("tenant-1", context.getTenantId());
        assertEquals("SHARED_API", context.getRequestSource());
        assertEquals("SharedCommandScope[content-redacted]", scope.toString());
        assertFalse(scope.toString().contains("shk-secret"));
        assertEquals(Integer.MAX_VALUE - 3, adapter.order());
        assertEquals("scoped-shared-task-create-command", adapter.name());
        assertFalse(adapter.supports(canonicalRequest(scope)));

        assertThrows(IllegalStateException.class,
                () -> adapter.mintScope("shk-secret", "not-a-canonical-uuid"));

        AgentTaskSubmitRequest request = canonicalRequest(scope);
        IllegalStateException notConsumed = assertThrows(IllegalStateException.class,
                () -> adapter.executeScoped(
                        scope, request, noOpParticipants(), () -> "not-consumed"));
        assertEquals("SHARED_TASK_CREATE_SCOPE_NOT_CONSUMED", notConsumed.getMessage());
        assertFalse(adapter.supports(request));
        IllegalStateException reused = assertThrows(IllegalStateException.class,
                () -> adapter.executeScoped(
                        scope, request, noOpParticipants(), () -> "reused"));
        assertEquals("SHARED_TASK_CREATE_SCOPE_ALREADY_USED", reused.getMessage());

        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope outer = mintScope(REQUEST_ID);
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope inner =
                mintScope("550e8400-e29b-41d4-a716-446655440001");
        AgentTaskSubmitRequest outerRequest = canonicalRequest(outer);
        AgentTaskSubmitRequest innerRequest = canonicalRequest(inner);
        IllegalStateException poisoned = assertThrows(IllegalStateException.class,
                () -> adapter.executeScoped(
                        outer,
                        outerRequest,
                        noOpParticipants(),
                        () -> {
                            IllegalStateException nested = assertThrows(
                                    IllegalStateException.class,
                                    () -> adapter.executeScoped(
                                            inner,
                                            innerRequest,
                                            noOpParticipants(),
                                            () -> "nested"));
                            assertEquals("SHARED_TASK_CREATE_SCOPE_NESTED", nested.getMessage());
                            return "outer";
                        }));
        assertEquals("SHARED_TASK_CREATE_SCOPE_POISONED", poisoned.getMessage());
        assertFalse(adapter.supports(outerRequest));

        ScopedSharedTaskCreateCommandAdapter otherAdapter =
                new ScopedSharedTaskCreateCommandAdapter(
                        taskDispatchFacade,
                        commandCoordinator,
                        serverAuthority,
                        sharingKeyService);
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope foreignNested =
                mintScope(otherAdapter, requestId(2));
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope foreignOuter =
                mintScope(requestId(3));
        AgentTaskSubmitRequest foreignOuterRequest = canonicalRequest(foreignOuter);
        IllegalStateException foreignNestedPoison = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        foreignOuter,
                        foreignOuterRequest,
                        noOpParticipants(),
                        () -> {
                            IllegalStateException nested = assertThrows(
                                    IllegalStateException.class,
                                    () -> adapter.executeScoped(
                                            foreignNested,
                                            canonicalRequest(foreignNested),
                                            noOpParticipants(),
                                            () -> "foreign-nested"));
                            assertEquals("SHARED_TASK_CREATE_SCOPE_NESTED",
                                    nested.getMessage());
                            return "outer";
                        }));
        assertEquals("SHARED_TASK_CREATE_SCOPE_POISONED",
                foreignNestedPoison.getMessage());

        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope nullOuter =
                mintScope(requestId(4));
        AgentTaskSubmitRequest nullOuterRequest = canonicalRequest(nullOuter);
        IllegalStateException nullNestedPoison = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        nullOuter,
                        nullOuterRequest,
                        noOpParticipants(),
                        () -> {
                            IllegalStateException nested = assertThrows(
                                    IllegalStateException.class,
                                    () -> adapter.executeScoped(null, null, null, null));
                            assertEquals("SHARED_TASK_CREATE_SCOPE_NESTED",
                                    nested.getMessage());
                            return "outer";
                        }));
        assertEquals("SHARED_TASK_CREATE_SCOPE_POISONED",
                nullNestedPoison.getMessage());

        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope foreign =
                otherAdapter.mintScope("shk-secret", requestId(5));
        IllegalStateException foreignIssuer = assertThrows(IllegalStateException.class,
                () -> adapter.executeScoped(
                        foreign,
                        canonicalRequest(foreign),
                        noOpParticipants(),
                        () -> "foreign"));
        assertEquals("SHARED_TASK_CREATE_SCOPE_ISSUER_CONFLICT", foreignIssuer.getMessage());
        assertFalse(adapter.supports(null));

        AgentTaskSubmitRequest unscopedShared = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .prompt("legacy shared")
                .resolveContext(AgentResolveContext.builder()
                        .requestSource("SHARED_API")
                        .build())
                .build();
        DispatchTaskDTO legacyTask = exactTask("legacy-task");
        A2aTask legacyA2a = A2aTask.builder().id("legacy-task").build();
        when(taskDispatchFacade.submitTaskDispatch(same(unscopedShared)))
                .thenReturn(legacyTask);
        when(taskDispatchFacade.toA2aTask(same(legacyTask))).thenReturn(legacyA2a);
        DefaultAgentSubmitPipeline realPipeline = new DefaultAgentSubmitPipeline(List.of(
                new TaskDispatchSubmitPipelineStage(taskDispatchFacade),
                adapter));

        AgentTaskSubmitResult legacyResult = realPipeline.submit(unscopedShared);

        assertSame(legacyTask, legacyResult.getDispatchTask());
        assertSame(legacyA2a, legacyResult.getTask());
        verify(taskDispatchFacade).submitTaskDispatch(same(unscopedShared));
        verifyNoInteractions(commandCoordinator, chain);
        verify(sharingKeyRepository, never()).findByIdForUpdate(anyString());
    }

    @Test
    void scopedFreshBuildsDedicatedBindingAndRunsLockedPolicyPrepareProviderCompletion() {
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope = mintScope(REQUEST_ID);
        Bound bound = bind(scope);
        SharingKeyEntity locked = sharingKeyEntity();
        locked.setMaxTurns(7);
        locked.setSystemPrompt("latest locked default");
        when(sharingKeyRepository.findByIdForUpdate("key-1"))
                .thenReturn(Optional.of(locked));
        DispatchTaskDTO fresh = exactTask("task-fresh");
        A2aTask a2aTask = A2aTask.builder().id("task-fresh").build();
        List<String> order = new ArrayList<>();
        ScopedSharedTaskCreateCommandAdapter.FreshParticipants participants =
                new ScopedSharedTaskCreateCommandAdapter.FreshParticipants() {
                    @Override
                    public void prepareFreshTask() {
                        order.add("prepare");
                        assertEquals(7, bound.dispatchRequest.getMaxTurns());
                        assertEquals("explicit override",
                                bound.dispatchRequest.getMetadata().get("systemPrompt"));
                        assertEquals(7,
                                bound.dispatchRequest.getMetadata().get("maxTurns"));
                    }

                    @Override
                    public void completeFreshTask(DispatchTaskDTO freshTask) {
                        order.add("complete");
                        assertSame(fresh, freshTask);
                    }
                };
        when(commandCoordinator.execute(
                same(bound.dispatchRequest),
                same(bound.context),
                same(bound.plan),
                any(),
                any(),
                any()))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.TaskCreateParticipants hooks =
                            invocation.getArgument(5);
                    hooks.afterEffectPermitBeforeRoutePreparation();
                    hooks.prepareFreshTask();
                    order.add("provider");
                    hooks.completeFreshTask(fresh);
                    return new TaskCreateCommandCoordinator.Executed(
                            new TaskCreateCommandCoordinator.TaskReference("task-fresh"), fresh);
                });
        when(taskDispatchFacade.toA2aTask(same(fresh))).thenReturn(a2aTask);

        AgentTaskSubmitResult result = adapter.executeScoped(
                scope,
                bound.submitRequest,
                participants,
                () -> adapter.handle(bound.submitRequest, chain));

        assertSame(fresh, result.getDispatchTask());
        assertSame(a2aTask, result.getTask());
        assertEquals(List.of("prepare", "provider", "complete"), order);
        verify(sharingKeyRepository).save(locked);
        verifyNoInteractions(chain);

        ArgumentCaptor<CanonicalCommandEnvelope> envelopeCaptor =
                ArgumentCaptor.forClass(CanonicalCommandEnvelope.class);
        ArgumentCaptor<VerifiedCommandAuthorizationDecision> decisionCaptor =
                ArgumentCaptor.forClass(VerifiedCommandAuthorizationDecision.class);
        verify(commandCoordinator).execute(
                same(bound.dispatchRequest),
                same(bound.context),
                same(bound.plan),
                envelopeCaptor.capture(),
                decisionCaptor.capture(),
                any());
        CanonicalCommandEnvelope envelope = envelopeCaptor.getValue();
        CanonicalCommandEnvelope.CommandBinding binding = envelope.binding();
        assertEquals(CanonicalCommandEnvelope.CommandIngress.SHARED,
                binding.ingress().ingress());
        assertEquals("NAVIGATOR_SHARED_API", binding.ingress().clientSurface());
        assertEquals("/api/v1/shared/ask", binding.ingress().routeId());
        assertEquals(REQUEST_ID, binding.request().clientRequestId());
        assertEquals(AuthorizationPrincipalType.SHARE_GRANTEE,
                binding.actor().principalType());
        assertEquals(AuthorizationCredentialLane.SHARING_KEY_CAPABILITY,
                binding.actor().lane());
        assertEquals(64, binding.actor().fingerprint().length());
        assertEquals("owner-1", binding.ownership().ownerReference());
        assertNull(binding.ownership().clientAppReference());
        assertNull(binding.ownership().upstreamReference());
        assertEquals(binding, serverAuthority.requireVerified(
                envelope, decisionCaptor.getValue()));
        assertFalse(envelope.toString().contains("shk-secret"));
        assertFalse(envelope.toString().contains("latest locked default"));
        assertFalse(envelope.toString().contains("prompt-secret"));

        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope tamperScope =
                mintScope(requestId(6));
        Bound tamperBound = bind(tamperScope);
        SharingKeyEntity tamperLocked = sharingKeyEntity();
        tamperLocked.setMaxTurns(11);
        when(sharingKeyRepository.findByIdForUpdate("key-1"))
                .thenReturn(Optional.of(tamperLocked));
        AtomicInteger tamperProvider = new AtomicInteger();
        when(commandCoordinator.execute(
                same(tamperBound.dispatchRequest),
                same(tamperBound.context),
                same(tamperBound.plan),
                any(),
                any(),
                any()))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.TaskCreateParticipants hooks =
                            invocation.getArgument(5);
                    hooks.afterEffectPermitBeforeRoutePreparation();
                    hooks.prepareFreshTask();
                    tamperProvider.incrementAndGet();
                    return new TaskCreateCommandCoordinator.Executed(
                            new TaskCreateCommandCoordinator.TaskReference("must-not-dispatch"),
                            exactTask("must-not-dispatch"));
                });

        IllegalStateException tamperFailure = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        tamperScope,
                        tamperBound.submitRequest,
                        new ScopedSharedTaskCreateCommandAdapter.FreshParticipants() {
                            @Override
                            public void prepareFreshTask() {
                                tamperBound.dispatchRequest.setMaxTurns(99);
                            }

                            @Override
                            public void completeFreshTask(DispatchTaskDTO freshTask) {
                                fail("completion must not run after locked policy tamper");
                            }
                        },
                        () -> adapter.handle(tamperBound.submitRequest, chain)));

        assertEquals("SHARED_TASK_CREATE_LOCKED_POLICY_CONFLICT",
                tamperFailure.getMessage());
        assertEquals(0, tamperProvider.get());
        verify(sharingKeyRepository).save(tamperLocked);
        verifyNoInteractions(chain);
    }

    @Test
    void realCoordinatorEnforcesPermitConsumeRoutePrepareProviderCompleteRecordOrder() {
        List<String> order = new ArrayList<>();
        TaskCreateCommandCoordinator realCoordinator =
                new TaskCreateCommandCoordinator(taskDispatchFacade, receiptService);
        ScopedSharedTaskCreateCommandAdapter realAdapter =
                new ScopedSharedTaskCreateCommandAdapter(
                        taskDispatchFacade,
                        realCoordinator,
                        serverAuthority,
                        sharingKeyService);
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope =
                mintScope(realAdapter, REQUEST_ID);
        Bound bound = bind(scope);
        bound.dispatchRequest.setMaxTurns(null);
        bound.dispatchRequest.setMetadata(Map.of("unrelated", "kept"));
        SharingKeyEntity locked = sharingKeyEntity();
        locked.setMaxTurns(9);
        locked.setSystemPrompt("locked policy");
        when(sharingKeyRepository.findByIdForUpdate("key-1"))
                .thenAnswer(invocation -> {
                    order.add("consume");
                    return Optional.of(locked);
                });
        when(receiptService.prepare(any(), any())).thenAnswer(invocation -> {
            order.add("receipt-prepare");
            return prepared(REQUEST_ID);
        });
        CommandOnceReceiptService.EffectPermit permit = permitted(REQUEST_ID, "attempt-1");
        when(receiptService.beginEffect(any(), any())).thenAnswer(invocation -> {
            order.add("begin-effect");
            return permit;
        });
        DispatchTaskDTO fresh = exactTask("task-real");
        when(taskDispatchFacade.createTask(
                same(bound.dispatchRequest),
                same(bound.context),
                same(bound.plan),
                any(TaskCreateCommandCoordinator.ProviderEffectGate.class)))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.ProviderEffectGate gate =
                            invocation.getArgument(3);
                    TaskCreateCommandCoordinator.ProviderEffectIdentity identity =
                            effectIdentity(bound);
                    return gate.invokePrepared(
                            bound.plan,
                            () -> identity,
                            () -> order.add("route"),
                            () -> TaskCreateCommandCoordinator.PreparedProviderEffect.capture(
                                    identity,
                                    "captured-provider-input",
                                    ignored -> {
                                        order.add("provider");
                                        return fresh;
                                    }));
                });
        doAnswer(invocation -> {
            order.add("record-result");
            return null;
        }).when(receiptService).recordResult(
                REQUEST_ID, "attempt-1", "TASK:task-real", "TASK_CREATED");
        when(taskDispatchFacade.toA2aTask(same(fresh)))
                .thenReturn(A2aTask.builder().id("task-real").build());

        AgentTaskSubmitResult result = realAdapter.executeScoped(
                scope,
                bound.submitRequest,
                new ScopedSharedTaskCreateCommandAdapter.FreshParticipants() {
                    @Override
                    public void prepareFreshTask() {
                        order.add("prepare");
                        assertEquals(9, bound.dispatchRequest.getMaxTurns());
                        assertEquals("locked policy",
                                bound.dispatchRequest.getMetadata().get("systemPrompt"));
                        assertEquals(9,
                                bound.dispatchRequest.getMetadata().get("maxTurns"));
                        assertEquals("kept",
                                bound.dispatchRequest.getMetadata().get("unrelated"));
                    }

                    @Override
                    public void completeFreshTask(DispatchTaskDTO freshTask) {
                        order.add("complete");
                    }
                },
                () -> realAdapter.handle(bound.submitRequest, chain));

        assertEquals("task-real", result.getDispatchTask().getTaskId());
        assertEquals(List.of(
                "receipt-prepare",
                "begin-effect",
                "consume",
                "route",
                "prepare",
                "provider",
                "complete",
                "record-result"), order);
        verifyNoInteractions(chain);
        verify(receiptService, never()).markAmbiguous(any(), any(), any());
    }

    @Test
    void beginEffectRecordedReplayHydratesExactTaskWithZeroQuotaRouteProviderCallbacks() {
        TaskCreateCommandCoordinator realCoordinator =
                new TaskCreateCommandCoordinator(taskDispatchFacade, receiptService);
        ScopedSharedTaskCreateCommandAdapter realAdapter =
                new ScopedSharedTaskCreateCommandAdapter(
                        taskDispatchFacade,
                        realCoordinator,
                        serverAuthority,
                        sharingKeyService);

        String initialRequestId = requestId(10);
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope initialScope =
                mintScope(realAdapter, initialRequestId);
        Bound initialBound = bind(initialScope);
        when(receiptService.prepare(any(), any())).thenReturn(
                new CommandOnceReceiptService.PrepareResult(
                        CommandOnceReceiptService.PrepareDisposition.EXACT_REPLAY,
                        snapshot(
                                initialRequestId,
                                CommandOnceReceiptService.ReceiptState.RESULT_RECORDED,
                                "attempt-initial",
                                "TASK:task-initial-recorded")));
        DispatchTaskDTO initialDurable = exactTask("task-initial-recorded");
        when(taskDispatchFacade.getTask(
                "task-initial-recorded", initialBound.context))
                .thenReturn(Optional.of(initialDurable));
        when(taskDispatchFacade.toA2aTask(same(initialDurable)))
                .thenReturn(A2aTask.builder().id("task-initial-recorded").build());
        AtomicInteger initialCallbacks = new AtomicInteger();

        AgentTaskSubmitResult initialReplay = realAdapter.executeScoped(
                initialScope,
                initialBound.submitRequest,
                countingParticipants(initialCallbacks),
                () -> realAdapter.handle(initialBound.submitRequest, chain));

        assertSame(initialDurable, initialReplay.getDispatchTask());
        assertEquals(0, initialCallbacks.get());

        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope =
                mintScope(realAdapter, REQUEST_ID);
        Bound bound = bind(scope);
        when(receiptService.prepare(any(), any())).thenReturn(prepared(REQUEST_ID));
        CommandOnceReceiptService.EffectPermit recorded = effectPermit(
                CommandOnceReceiptService.BeginEffectDisposition.RESULT_RECORDED,
                REQUEST_ID,
                CommandOnceReceiptService.ReceiptState.RESULT_RECORDED,
                "attempt-1",
                "TASK:task-recorded");
        when(receiptService.beginEffect(any(), any())).thenReturn(recorded);
        AtomicInteger route = new AtomicInteger();
        AtomicInteger provider = new AtomicInteger();
        when(taskDispatchFacade.createTask(
                same(bound.dispatchRequest),
                same(bound.context),
                same(bound.plan),
                any(TaskCreateCommandCoordinator.ProviderEffectGate.class)))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.ProviderEffectGate gate =
                            invocation.getArgument(3);
                    TaskCreateCommandCoordinator.ProviderEffectIdentity identity =
                            effectIdentity(bound);
                    return gate.invokePrepared(
                            bound.plan,
                            () -> identity,
                            route::incrementAndGet,
                            () -> TaskCreateCommandCoordinator.PreparedProviderEffect.capture(
                                    identity,
                                    "captured-provider-input",
                                    ignored -> {
                                        provider.incrementAndGet();
                                        return exactTask("must-not-dispatch");
                                    }));
                });
        DispatchTaskDTO durable = exactTask("task-recorded");
        when(taskDispatchFacade.getTask("task-recorded", bound.context))
                .thenReturn(Optional.of(durable));
        when(taskDispatchFacade.toA2aTask(same(durable)))
                .thenReturn(A2aTask.builder().id("task-recorded").build());
        AtomicInteger callbacks = new AtomicInteger();

        AgentTaskSubmitResult replay = realAdapter.executeScoped(
                scope,
                bound.submitRequest,
                countingParticipants(callbacks),
                () -> realAdapter.handle(bound.submitRequest, chain));

        assertSame(durable, replay.getDispatchTask());
        assertEquals(0, callbacks.get());
        assertEquals(0, route.get());
        assertEquals(0, provider.get());
        verify(sharingKeyRepository, never()).findByIdForUpdate(anyString());
        verify(sharingKeyRepository, never()).save(any());
        verify(receiptService, times(1)).beginEffect(any(), any());
        verify(taskDispatchFacade, times(1)).createTask(
                any(), any(), any(), any());
        verify(receiptService, never()).recordResult(any(), any(), any(), any());
        verifyNoInteractions(chain);
    }

    @Test
    void lockedQuotaFailureMarksExactAttemptAmbiguousBeforeRouteProviderOrCompletion() {
        TaskCreateCommandCoordinator realCoordinator =
                new TaskCreateCommandCoordinator(taskDispatchFacade, receiptService);
        ScopedSharedTaskCreateCommandAdapter realAdapter =
                new ScopedSharedTaskCreateCommandAdapter(
                        taskDispatchFacade,
                        realCoordinator,
                        serverAuthority,
                        sharingKeyService);
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope =
                mintScope(realAdapter, REQUEST_ID);
        Bound bound = bind(scope);
        when(receiptService.prepare(any(), any())).thenReturn(prepared(REQUEST_ID));
        CommandOnceReceiptService.EffectPermit permit = permitted(REQUEST_ID, "attempt-1");
        when(receiptService.beginEffect(any(), any())).thenReturn(permit);
        SharingKeyEntity exhausted = sharingKeyEntity();
        exhausted.setCallDate(LocalDate.now());
        exhausted.setMaxDailyCalls(1);
        exhausted.setTodayCalls(1);
        when(sharingKeyRepository.findByIdForUpdate("key-1"))
                .thenReturn(Optional.of(exhausted));
        AtomicInteger route = new AtomicInteger();
        AtomicInteger provider = new AtomicInteger();
        when(taskDispatchFacade.createTask(
                same(bound.dispatchRequest),
                same(bound.context),
                same(bound.plan),
                any(TaskCreateCommandCoordinator.ProviderEffectGate.class)))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.ProviderEffectGate gate =
                            invocation.getArgument(3);
                    TaskCreateCommandCoordinator.ProviderEffectIdentity identity =
                            effectIdentity(bound);
                    return gate.invokePrepared(
                            bound.plan,
                            () -> identity,
                            route::incrementAndGet,
                            () -> TaskCreateCommandCoordinator.PreparedProviderEffect.capture(
                                    identity,
                                    "captured-provider-input",
                                    ignored -> {
                                        provider.incrementAndGet();
                                        return exactTask("must-not-dispatch");
                                    }));
                });
        AtomicInteger callbacks = new AtomicInteger();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> realAdapter.executeScoped(
                        scope,
                        bound.submitRequest,
                        countingParticipants(callbacks),
                        () -> realAdapter.handle(bound.submitRequest, chain)));

        assertTrue(failure.getMessage().contains("Daily call limit exceeded"));
        assertEquals(0, callbacks.get());
        assertEquals(0, route.get());
        assertEquals(0, provider.get());
        verify(receiptService).markAmbiguous(
                REQUEST_ID,
                "attempt-1",
                TaskCreateCommandCoordinator.TASK_CREATE_OUTCOME_UNKNOWN);
        verify(receiptService, never()).recordResult(any(), any(), any(), any());
        verify(sharingKeyRepository, never()).save(any());
        verifyNoInteractions(chain);
    }

    @Test
    void initialAndGateNonPermitStatesHaveZeroSharedEffects() {
        TaskCreateCommandCoordinator realCoordinator =
                new TaskCreateCommandCoordinator(taskDispatchFacade, receiptService);
        ScopedSharedTaskCreateCommandAdapter realAdapter =
                new ScopedSharedTaskCreateCommandAdapter(
                        taskDispatchFacade,
                        realCoordinator,
                        serverAuthority,
                        sharingKeyService);

        assertInitialNonPermit(
                realAdapter,
                requestId(20),
                CommandOnceReceiptService.ReceiptState.EFFECT_STARTED,
                "TASK_CREATE_EFFECT_ALREADY_STARTED");
        assertInitialNonPermit(
                realAdapter,
                requestId(21),
                CommandOnceReceiptService.ReceiptState.AMBIGUOUS,
                "TASK_CREATE_EFFECT_AMBIGUOUS");
        assertGateNonPermit(
                realAdapter,
                requestId(22),
                CommandOnceReceiptService.BeginEffectDisposition.ALREADY_STARTED,
                CommandOnceReceiptService.ReceiptState.EFFECT_STARTED,
                "TASK_CREATE_EFFECT_ALREADY_STARTED");
        assertGateNonPermit(
                realAdapter,
                requestId(23),
                CommandOnceReceiptService.BeginEffectDisposition.AMBIGUOUS,
                CommandOnceReceiptService.ReceiptState.AMBIGUOUS,
                "TASK_CREATE_EFFECT_AMBIGUOUS");

        String bindingRequestId = requestId(24);
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope bindingScope =
                mintScope(realAdapter, bindingRequestId);
        Bound bindingBound = bind(bindingScope);
        when(receiptService.prepare(
                argThat(envelope -> bindingRequestId.equals(
                        envelope.binding().request().clientRequestId())),
                any()))
                .thenThrow(new IllegalStateException("COMMAND_ONCE_BINDING_CONFLICT"));
        AtomicInteger bindingCallbacks = new AtomicInteger();

        IllegalStateException bindingFailure = assertThrows(
                IllegalStateException.class,
                () -> realAdapter.executeScoped(
                        bindingScope,
                        bindingBound.submitRequest,
                        countingParticipants(bindingCallbacks),
                        () -> realAdapter.handle(bindingBound.submitRequest, chain)));

        assertEquals("COMMAND_ONCE_BINDING_CONFLICT", bindingFailure.getMessage());
        assertEquals(0, bindingCallbacks.get());
        assertFalse(realAdapter.supports(bindingBound.submitRequest));
        verify(receiptService, times(2)).beginEffect(any(), any());
        verify(taskDispatchFacade, times(2)).createTask(any(), any(), any(), any());
        verify(sharingKeyRepository, never()).findByIdForUpdate(anyString());
        verify(sharingKeyRepository, never()).save(any());
        verify(receiptService, never()).recordResult(any(), any(), any(), any());
        verifyNoInteractions(chain);
    }

    @Test
    void recordedHydrateRejectsMissingAndEveryIdentityDriftWithoutRedispatch() {
        assertRecordedHydrateFailure(
                requestId(30),
                "SHARED_TASK_CREATE_RECORDED_TASK_UNAVAILABLE",
                null);
        List<HydrateDrift> drifts = List.of(
                new HydrateDrift(
                        "SHARED_TASK_CREATE_RECORDED_TASK_ID_CONFLICT",
                        task -> task.setTaskId("other-task")),
                new HydrateDrift(
                        "SHARED_TASK_CREATE_RECORDED_PROVIDER_CONFLICT",
                        task -> task.setProviderType("other-provider")),
                new HydrateDrift(
                        "SHARED_TASK_CREATE_RECORDED_AGENT_CONFLICT",
                        task -> task.setAgentId("other-agent")),
                new HydrateDrift(
                        "SHARED_TASK_CREATE_RECORDED_WORKER_CONFLICT",
                        task -> task.setWorkerId("other-worker")),
                new HydrateDrift(
                        "SHARED_TASK_CREATE_RECORDED_MODEL_CONFIG_CONFLICT",
                        task -> task.setModelConfigId("other-model-config")),
                new HydrateDrift(
                        "SHARED_TASK_CREATE_RECORDED_MODEL_CONFLICT",
                        task -> task.setModel("other-model")),
                new HydrateDrift(
                        "SHARED_TASK_CREATE_RECORDED_SESSION_CONFLICT",
                        task -> task.setSessionId("other-session")),
                new HydrateDrift(
                        "SHARED_TASK_CREATE_RECORDED_DIRECTORY_CONFLICT",
                        task -> task.setDirectoryId("other-directory")));
        for (int i = 0; i < drifts.size(); i++) {
            HydrateDrift drift = drifts.get(i);
            assertRecordedHydrateFailure(
                    requestId(31 + i), drift.safeCode(), drift.mutation());
        }

        verify(sharingKeyRepository, never()).findByIdForUpdate(anyString());
        verify(sharingKeyRepository, never()).save(any());
        verify(taskDispatchFacade, never()).createTask(any(), any(), any(), any());
        verify(taskDispatchFacade, never()).submitTaskDispatch(any());
        verifyNoInteractions(chain);
    }

    @Test
    void scopeAndPlanTamperFailClosedWithoutQuotaCoordinatorOrFallback() {
        List<Consumer<Bound>> authorityTamper = List.of(
                bound -> bound.submitRequest.setClientRequestId(requestId(999)),
                bound -> bound.submitRequest.setAgentId("other-agent"),
                bound -> bound.context.setRequestSource("A2A"),
                bound -> bound.context.setUserId("other-owner"),
                bound -> bound.context.setTenantId("other-tenant"),
                bound -> bound.submitRequest.setResolveContext(null));
        for (int i = 0; i < authorityTamper.size(); i++) {
            ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope =
                    mintScope(requestId(50 + i));
            Bound bound = bind(scope);
            authorityTamper.get(i).accept(bound);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> adapter.executeScoped(
                            scope,
                            bound.submitRequest,
                            noOpParticipants(),
                            () -> adapter.handle(bound.submitRequest, chain)));

            assertEquals("SHARED_TASK_CREATE_SCOPE_AUTHORITY_CONFLICT",
                    failure.getMessage());
            assertFalse(adapter.supports(bound.submitRequest));
        }

        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope requestScope =
                mintScope(requestId(60));
        Bound requestBound = bind(requestScope);
        AgentTaskSubmitRequest differentRequest = canonicalRequest(requestScope);
        IllegalStateException requestFailure = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        requestScope,
                        requestBound.submitRequest,
                        noOpParticipants(),
                        () -> adapter.handle(differentRequest, chain)));
        assertEquals("SHARED_TASK_CREATE_SCOPE_REQUEST_CONFLICT",
                requestFailure.getMessage());

        List<Consumer<TaskCreateTargetResolver.CreateExecutionPlan>> planTamper = List.of(
                plan -> when(plan.ownerUserId()).thenReturn("other-owner"),
                plan -> when(plan.tenantId()).thenReturn("other-tenant"),
                plan -> when(plan.logicalAgentId()).thenReturn("other-agent"));
        for (int i = 0; i < planTamper.size(); i++) {
            ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope =
                    mintScope(requestId(61 + i));
            Bound bound = bind(scope);
            planTamper.get(i).accept(bound.plan);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> adapter.executeScoped(
                            scope,
                            bound.submitRequest,
                            noOpParticipants(),
                            () -> adapter.handle(bound.submitRequest, chain)));

            assertEquals("SHARED_TASK_CREATE_SCOPE_PLAN_AUTHORITY_CONFLICT",
                    failure.getMessage());
            assertFalse(adapter.supports(bound.submitRequest));
        }

        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope nullPlanScope =
                mintScope(requestId(64));
        Bound nullPlanBound = bind(nullPlanScope);
        when(taskDispatchFacade.resolveCreateExecutionPlan(
                same(nullPlanBound.dispatchRequest), same(nullPlanBound.context)))
                .thenReturn(null);
        IllegalStateException nullPlanFailure = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        nullPlanScope,
                        nullPlanBound.submitRequest,
                        noOpParticipants(),
                        () -> adapter.handle(nullPlanBound.submitRequest, chain)));
        assertEquals("SHARED_TASK_CREATE_SCOPE_PLAN_AUTHORITY_CONFLICT",
                nullPlanFailure.getMessage());

        verifyNoInteractions(commandCoordinator, chain);
        verify(sharingKeyRepository, never()).findByIdForUpdate(anyString());
        verify(sharingKeyRepository, never()).save(any());
        assertFalse(adapter.supports(nullPlanBound.submitRequest));
    }

    private void assertInitialNonPermit(
            ScopedSharedTaskCreateCommandAdapter targetAdapter,
            String requestId,
            CommandOnceReceiptService.ReceiptState state,
            String expectedSafeCode) {
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope =
                mintScope(targetAdapter, requestId);
        Bound bound = bind(scope);
        when(receiptService.prepare(any(), any())).thenReturn(
                new CommandOnceReceiptService.PrepareResult(
                        CommandOnceReceiptService.PrepareDisposition.EXACT_REPLAY,
                        snapshot(requestId, state, "attempt-" + requestId, null)));
        AtomicInteger callbacks = new AtomicInteger();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> targetAdapter.executeScoped(
                        scope,
                        bound.submitRequest,
                        countingParticipants(callbacks),
                        () -> targetAdapter.handle(bound.submitRequest, chain)));

        assertEquals(expectedSafeCode, failure.getMessage());
        assertEquals(0, callbacks.get());
        assertFalse(targetAdapter.supports(bound.submitRequest));
    }

    private void assertGateNonPermit(
            ScopedSharedTaskCreateCommandAdapter targetAdapter,
            String requestId,
            CommandOnceReceiptService.BeginEffectDisposition disposition,
            CommandOnceReceiptService.ReceiptState state,
            String expectedSafeCode) {
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope =
                mintScope(targetAdapter, requestId);
        Bound bound = bind(scope);
        when(receiptService.prepare(any(), any())).thenReturn(prepared(requestId));
        CommandOnceReceiptService.EffectPermit permit = effectPermit(
                disposition,
                requestId,
                state,
                "attempt-" + requestId,
                null);
        when(receiptService.beginEffect(any(), any())).thenReturn(permit);
        AtomicInteger route = new AtomicInteger();
        AtomicInteger provider = new AtomicInteger();
        when(taskDispatchFacade.createTask(
                same(bound.dispatchRequest),
                same(bound.context),
                same(bound.plan),
                any(TaskCreateCommandCoordinator.ProviderEffectGate.class)))
                .thenAnswer(invocation -> {
                    TaskCreateCommandCoordinator.ProviderEffectGate gate =
                            invocation.getArgument(3);
                    TaskCreateCommandCoordinator.ProviderEffectIdentity identity =
                            effectIdentity(bound);
                    return gate.invokePrepared(
                            bound.plan,
                            () -> identity,
                            route::incrementAndGet,
                            () -> TaskCreateCommandCoordinator.PreparedProviderEffect.capture(
                                    identity,
                                    "captured-provider-input",
                                    ignored -> {
                                        provider.incrementAndGet();
                                        return exactTask("must-not-dispatch");
                                    }));
                });
        AtomicInteger callbacks = new AtomicInteger();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> targetAdapter.executeScoped(
                        scope,
                        bound.submitRequest,
                        countingParticipants(callbacks),
                        () -> targetAdapter.handle(bound.submitRequest, chain)));

        assertEquals(expectedSafeCode, failure.getMessage());
        assertEquals(0, callbacks.get());
        assertEquals(0, route.get());
        assertEquals(0, provider.get());
        assertFalse(targetAdapter.supports(bound.submitRequest));
    }

    private void assertRecordedHydrateFailure(
            String requestId,
            String expectedSafeCode,
            Consumer<DispatchTaskDTO> mutation) {
        ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope =
                mintScope(requestId);
        Bound bound = bind(scope);
        String taskId = "task-recorded-" + requestId;
        when(commandCoordinator.execute(
                same(bound.dispatchRequest),
                same(bound.context),
                same(bound.plan),
                any(),
                any(),
                any()))
                .thenReturn(new TaskCreateCommandCoordinator.RecordedReplay(
                        new TaskCreateCommandCoordinator.TaskReference(taskId)));
        if (mutation == null) {
            when(taskDispatchFacade.getTask(taskId, bound.context))
                    .thenReturn(Optional.empty());
        } else {
            DispatchTaskDTO durable = exactTask(taskId);
            mutation.accept(durable);
            when(taskDispatchFacade.getTask(taskId, bound.context))
                    .thenReturn(Optional.of(durable));
        }
        AtomicInteger callbacks = new AtomicInteger();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> adapter.executeScoped(
                        scope,
                        bound.submitRequest,
                        countingParticipants(callbacks),
                        () -> adapter.handle(bound.submitRequest, chain)));

        assertEquals(expectedSafeCode, failure.getMessage());
        assertEquals(0, callbacks.get());
        assertFalse(adapter.supports(bound.submitRequest));
        verify(taskDispatchFacade).getTask(taskId, bound.context);
    }

    private ScopedSharedTaskCreateCommandAdapter.SharedCommandScope mintScope(
            String requestId) {
        return mintScope(adapter, requestId);
    }

    private ScopedSharedTaskCreateCommandAdapter.SharedCommandScope mintScope(
            ScopedSharedTaskCreateCommandAdapter targetAdapter,
            String requestId) {
        SharingKeyEntity preflight = sharingKeyEntity();
        preflight.setMaxTurns(2);
        preflight.setSystemPrompt("preflight default");
        when(sharingKeyRepository.findBySharingKey("shk-secret"))
                .thenReturn(Optional.of(preflight));
        when(userRepository.findById("owner-1"))
                .thenReturn(Optional.of(owner()));
        return targetAdapter.mintScope("shk-secret", requestId);
    }

    private Bound bind(
            ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope) {
        AgentResolveContext context = scope.newResolveContext();
        AgentTaskSubmitRequest submitRequest = canonicalRequest(scope);
        submitRequest.setResolveContext(context);
        TaskDispatchRequest dispatchRequest = TaskDispatchRequest.builder()
                .agentId("agent-1")
                .providerType("claude-worker")
                .workerId("worker-1")
                .modelConfigId("model-config-1")
                .model("claude-sonnet")
                .directoryId("directory-1")
                .prompt("prompt-secret")
                .metadata(Map.of("systemPrompt", "explicit override"))
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = plan();
        lenient().when(taskDispatchFacade.toTaskDispatchRequest(same(submitRequest)))
                .thenReturn(dispatchRequest);
        lenient().when(taskDispatchFacade.resolveCreateExecutionPlan(
                same(dispatchRequest), same(context)))
                .thenReturn(plan);
        return new Bound(submitRequest, context, dispatchRequest, plan);
    }

    private AgentTaskSubmitRequest canonicalRequest(
            ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope) {
        return AgentTaskSubmitRequest.builder()
                .clientRequestId(scope.clientRequestId())
                .agentId(scope.agentId())
                .providerType("claude-worker")
                .workerId("worker-1")
                .modelConfigId("model-config-1")
                .model("claude-sonnet")
                .directoryId("directory-1")
                .prompt("prompt-secret")
                .metadata(Map.of("systemPrompt", "explicit override"))
                .resolveContext(scope.newResolveContext())
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
                .directoryId("directory-1")
                .status("PENDING")
                .build();
    }

    private SharingKeyEntity sharingKeyEntity() {
        SharingKeyEntity entity = new SharingKeyEntity();
        entity.setId("key-1");
        entity.setSharingKey("shk-secret");
        entity.setOwnerUserId("owner-1");
        entity.setAgentId("agent-1");
        entity.setEnabled(true);
        entity.setAllowedOperations("ask");
        entity.setMaxTurns(2);
        entity.setSystemPrompt("preflight default");
        entity.setMaxDailyCalls(10);
        entity.setTodayCalls(0);
        entity.setCallDate(LocalDate.now());
        entity.setExpiresAt(LocalDateTime.now().plusDays(1));
        return entity;
    }

    private UserEntity owner() {
        UserEntity owner = new UserEntity();
        owner.setId("owner-1");
        owner.setTenantId("tenant-1");
        return owner;
    }

    private TaskCreateCommandCoordinator.ProviderEffectIdentity effectIdentity(Bound bound) {
        return TaskCreateCommandCoordinator.ProviderEffectIdentity.atEffectPoint(
                TaskCreateTargetResolver.ExecutionRoute.A2A,
                bound.dispatchRequest,
                bound.context,
                "agent-1",
                "claude-worker");
    }

    private ScopedSharedTaskCreateCommandAdapter.FreshParticipants noOpParticipants() {
        return countingParticipants(new AtomicInteger());
    }

    private ScopedSharedTaskCreateCommandAdapter.FreshParticipants countingParticipants(
            AtomicInteger calls) {
        return new ScopedSharedTaskCreateCommandAdapter.FreshParticipants() {
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

    private static CommandOnceReceiptService.PrepareResult prepared(String requestId) {
        return new CommandOnceReceiptService.PrepareResult(
                CommandOnceReceiptService.PrepareDisposition.EXACT_REPLAY,
                snapshot(
                        requestId,
                        CommandOnceReceiptService.ReceiptState.PREPARED,
                        null,
                        null));
    }

    private static CommandOnceReceiptService.EffectPermit permitted(
            String requestId,
            String attemptId) {
        return effectPermit(
                CommandOnceReceiptService.BeginEffectDisposition.PERMITTED,
                requestId,
                CommandOnceReceiptService.ReceiptState.EFFECT_STARTED,
                attemptId,
                null);
    }

    private static CommandOnceReceiptService.EffectPermit effectPermit(
            CommandOnceReceiptService.BeginEffectDisposition disposition,
            String requestId,
            CommandOnceReceiptService.ReceiptState state,
            String attemptId,
            String resultReference) {
        CommandOnceReceiptService.EffectPermit permit =
                mock(CommandOnceReceiptService.EffectPermit.class);
        when(permit.disposition()).thenReturn(disposition);
        lenient().when(permit.providerEffectPermitted())
                .thenReturn(disposition == CommandOnceReceiptService.BeginEffectDisposition.PERMITTED);
        lenient().when(permit.snapshot()).thenReturn(
                snapshot(requestId, state, attemptId, resultReference));
        return permit;
    }

    private static CommandOnceReceiptService.ReceiptSnapshot snapshot(
            String requestId,
            CommandOnceReceiptService.ReceiptState state,
            String attemptId,
            String resultReference) {
        return new CommandOnceReceiptService.ReceiptSnapshot(
                "receipt-" + requestId,
                requestId,
                state,
                attemptId,
                resultReference,
                state.name(),
                "decision-1",
                NOW,
                NOW,
                NOW.plusSeconds(300),
                null,
                null,
                null,
                null,
                0L);
    }

    private static String requestId(int suffix) {
        return new UUID(0L, suffix).toString();
    }

    private record HydrateDrift(
            String safeCode,
            Consumer<DispatchTaskDTO> mutation) {
    }

    private record Bound(
            AgentTaskSubmitRequest submitRequest,
            AgentResolveContext context,
            TaskDispatchRequest dispatchRequest,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
    }
}
