package com.foggy.navigator.session.service;

import com.foggy.navigator.business.agent.service.A2AgentResourceResolver;
import com.foggy.navigator.business.agent.service.BusinessAgentSessionService;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.claude.worker.controller.openapi.OpenApiRuntimeTaskCreateFacade;
import com.foggy.navigator.claude.worker.controller.openapi.OpenApiRuntimeTaskLaunchPlanner;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.session.agent.pipeline.DefaultAgentSubmitPipeline;
import com.foggy.navigator.session.command.CommandOnceReceiptService;
import com.foggy.navigator.session.command.persistence.CommandOnceReceiptEntity;
import com.foggy.navigator.session.command.repository.CommandOnceReceiptRepository;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Composition evidence for the production Facade -> pipeline stage -> Coordinator -> receipt path.
 * External persistence and Provider boundaries remain mocks; the command components are real.
 */
class OpenApiRuntimeTaskCreateFacadeCompositionTest {

    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void recordedReplayUsesRealCommandPathWithZeroFreshMutationOrProviderDispatch() {
        Fixture fixture = fixture();
        DispatchTaskDTO recorded = exactTask("task-recorded", "SUBMITTED");
        AtomicInteger providerEffects = new AtomicInteger();
        stubProviderEffect(fixture, recorded, providerEffects);
        when(fixture.dispatchFacade.getTask(eq("task-recorded"), any()))
                .thenReturn(Optional.of(recorded));
        when(fixture.dispatchFacade.toA2aTask(recorded))
                .thenReturn(a2aTask("task-recorded", A2aTaskState.SUBMITTED));
        when(fixture.taskService.prepareOpenApiTaskScopedTokenAfterPreflight(
                any(), any(), any(), any(), any(), any(), any(), any(),
                eq(fixture.preflight)))
                .thenReturn(preparedToken());

        OpenApiRuntimeTaskCreateFacade.VerifiedCreateCommand command =
                command(fixture.facade, launchPlan());
        OpenApiRuntimeTaskCreateFacade.CreateOutcome fresh = fixture.facade.create(command);
        CommandOnceReceiptService.ReceiptSnapshot recordedReceipt =
                fixture.receiptService.find(REQUEST_ID).orElseThrow();

        assertTrue(fresh.created());
        assertEquals(1, providerEffects.get());
        assertEquals(CommandOnceReceiptService.ReceiptState.RESULT_RECORDED,
                recordedReceipt.state());
        assertEquals("TASK:task-recorded", recordedReceipt.opaqueResultReference());
        clearInvocations(
                fixture.dispatchFacade,
                fixture.receiptRepository,
                fixture.taskService,
                fixture.auditService,
                fixture.sessionQueryService,
                fixture.sessionService);

        OpenApiRuntimeTaskCreateFacade.CreateOutcome outcome = fixture.facade.create(
                command);

        assertTrue(outcome.created());
        assertEquals("task-recorded", outcome.task().getId());
        assertTrue(outcome.metadata().isEmpty());
        assertEquals(1, providerEffects.get());
        assertEquals(CommandOnceReceiptService.ReceiptState.RESULT_RECORDED,
                fixture.receiptService.find(REQUEST_ID).orElseThrow().state());
        verify(fixture.dispatchFacade, never()).createTask(any(), any(), any(), any());
        verify(fixture.receiptRepository, never()).saveAndFlush(any());
        verify(fixture.taskService, never()).prepareOpenApiTaskScopedTokenAfterPreflight(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.taskService, never()).revokeOpenApiTaskScopedToken(
                any(), any(), any(), any());
        verify(fixture.auditService, never()).taskAdmissionRecorded(any(), any());
        verify(fixture.auditService, never()).taskDispatchRecorded(any(), any());
        verify(fixture.sessionQueryService, never()).updateClientContextJson(
                any(), any(), any(), any());
        verify(fixture.sessionService, never()).bindOpenApiSession(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void immediateTerminalRevokeFailureMarksAmbiguousAndSecondAttemptNeverRedispatches() {
        Fixture fixture = fixture();
        when(fixture.taskService.prepareOpenApiTaskScopedTokenAfterPreflight(
                any(), any(), any(), any(), any(), any(), any(), any(),
                eq(fixture.preflight)))
                .thenReturn(preparedToken());
        doAnswer(invocation -> {
            throw new IllegalStateException("terminal revoke failed");
        }).when(fixture.taskService).revokeOpenApiTaskScopedToken(
                "tenant-1",
                "task-token-1",
                "system",
                "open api task returned terminal after submission");
        AtomicInteger providerEffects = new AtomicInteger();
        stubProviderEffect(
                fixture,
                exactTask("task-terminal", "FAILED"),
                providerEffects);

        OpenApiRuntimeTaskCreateFacade.VerifiedCreateCommand command =
                command(fixture.facade, launchPlan());
        OpenApiRuntimeTaskCreateFacade.CreateOutcome first = fixture.facade.create(command);
        CommandOnceReceiptService.ReceiptSnapshot ambiguousReceipt =
                fixture.receiptService.find(REQUEST_ID).orElseThrow();
        OpenApiRuntimeTaskCreateFacade.CreateOutcome second = fixture.facade.create(command);

        assertFalse(first.created());
        assertEquals("terminal revoke failed", first.rejectionMessage());
        assertTrue(first.metadata().isEmpty());
        assertFalse(second.created());
        assertEquals("TASK_CREATE_EFFECT_AMBIGUOUS", second.rejectionMessage());
        assertEquals(1, providerEffects.get());
        assertEquals(CommandOnceReceiptService.ReceiptState.AMBIGUOUS,
                ambiguousReceipt.state());
        assertTrue(ambiguousReceipt.effectAttemptId() != null
                && !ambiguousReceipt.effectAttemptId().isBlank());
        assertEquals("TASK_CREATE_OUTCOME_UNKNOWN", ambiguousReceipt.safeCode());
        assertEquals(null, ambiguousReceipt.opaqueResultReference());
        verify(fixture.dispatchFacade, times(1)).createTask(any(), any(), any(), any());
        verify(fixture.taskService, times(1)).prepareOpenApiTaskScopedTokenAfterPreflight(
                any(), any(), any(), any(), any(), any(), any(), any(),
                eq(fixture.preflight));
        verify(fixture.taskService, times(1)).revokeOpenApiTaskScopedToken(
                "tenant-1",
                "task-token-1",
                "system",
                "open api task returned terminal after submission");
        verify(fixture.auditService, times(1)).taskAdmissionRecorded(any(), any());
        verify(fixture.auditService, never()).taskDispatchRecorded(any(), any());
        verify(fixture.sessionQueryService, never()).updateClientContextJson(
                any(), any(), any(), any());
        verify(fixture.sessionService, never()).bindOpenApiSession(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    private Fixture fixture() {
        TaskDispatchFacade dispatchFacade = mock(TaskDispatchFacade.class);
        Clock authorityClock =
                Clock.fixed(Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC);
        VerifiedCommandAuthorizationDecision.ServerAuthority authority =
                new VerifiedCommandAuthorizationDecision.ServerAuthority(
                        "test.policy.v1",
                        authorityClock,
                        Duration.ofMinutes(5));
        CommandOnceReceiptRepository receiptRepository = inMemoryReceiptRepository();
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> mock(TransactionStatus.class));
        CommandOnceReceiptService receiptService = new CommandOnceReceiptService(
                receiptRepository,
                authority,
                authorityClock,
                transactionManager);
        TaskCreateCommandCoordinator coordinator =
                new TaskCreateCommandCoordinator(dispatchFacade, receiptService);
        ScopedOpenApiTaskCreateCommandAdapter adapter =
                new ScopedOpenApiTaskCreateCommandAdapter(
                        dispatchFacade,
                        coordinator,
                        authority);
        DefaultAgentSubmitPipeline pipeline = new DefaultAgentSubmitPipeline(List.of(adapter));

        TaskCreateTargetResolver.CreateExecutionPlan plan =
                mock(TaskCreateTargetResolver.CreateExecutionPlan.class);
        when(plan.executionRoute()).thenReturn(TaskCreateTargetResolver.ExecutionRoute.A2A);
        when(plan.tenantId()).thenReturn("tenant-1");
        when(plan.ownerUserId()).thenReturn("owner-1");
        when(plan.logicalAgentId()).thenReturn("agent-1");
        when(plan.providerType()).thenReturn("LANGGRAPH_BIZ");
        when(plan.physicalWorkerId()).thenReturn("worker-1");
        when(plan.modelConfigId()).thenReturn("model-1");
        when(plan.model()).thenReturn("model-variant-1");
        when(plan.sessionId()).thenReturn("session-1");
        when(plan.directoryId()).thenReturn("directory-1");
        when(dispatchFacade.toTaskDispatchRequest(any()))
                .thenAnswer(invocation -> canonicalRequest(invocation.getArgument(0)));
        when(dispatchFacade.resolveCreateExecutionPlan(any(), any())).thenReturn(plan);

        CodingAgentRepository codingAgentRepository = mock(CodingAgentRepository.class);
        CodingAgentEntity codingAgent = new CodingAgentEntity();
        codingAgent.setAgentId("agent-1");
        codingAgent.setTenantId("tenant-1");
        codingAgent.setUserId("owner-1");
        when(codingAgentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(codingAgent));
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        when(agentResolver.resolveAgent(eq("agent-1"), any()))
                .thenReturn(Optional.of(mock(A2aAgent.class)));
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        RuntimeRequestAuditService auditService = mock(RuntimeRequestAuditService.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        BusinessAgentSessionService sessionService = mock(BusinessAgentSessionService.class);
        BusinessAgentTaskService.OpenApiTaskWorkerPreflight preflight =
                mock(BusinessAgentTaskService.OpenApiTaskWorkerPreflight.class);
        when(preflight.workerId()).thenReturn("worker-1");
        when(preflight.modelConfigId()).thenReturn("model-1");
        when(preflight.workerPoolId()).thenReturn("pool-1");
        when(preflight.workerBackend()).thenReturn("LANGGRAPH_BIZ");
        when(preflight.upstreamSystemId()).thenReturn("upstream-system-1");
        when(taskService.resolveOpenApiTaskWorkerPreflight(
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    BusinessAgentWorkerTaskLaunchRequest selection = invocation.getArgument(7);
                    selection.setSelectedWorkerId("worker-1");
                    return preflight;
                });
        OpenApiRuntimeTaskCreateFacade facade = new OpenApiRuntimeTaskCreateFacade(
                codingAgentRepository,
                agentResolver,
                pipeline,
                adapter,
                sessionQueryService,
                provider(auditService),
                provider(taskService),
                provider(sessionService));
        return new Fixture(
                facade,
                dispatchFacade,
                receiptService,
                receiptRepository,
                taskService,
                auditService,
                sessionQueryService,
                sessionService,
                preflight,
                plan);
    }

    private CommandOnceReceiptRepository inMemoryReceiptRepository() {
        CommandOnceReceiptRepository repository = mock(CommandOnceReceiptRepository.class);
        AtomicReference<CommandOnceReceiptEntity> stored = new AtomicReference<>();
        when(repository.saveAndFlush(any(CommandOnceReceiptEntity.class)))
                .thenAnswer(invocation -> {
                    CommandOnceReceiptEntity receipt = invocation.getArgument(0);
                    stored.set(receipt);
                    return receipt;
                });
        when(repository.findByClientRequestId(any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get())
                        .filter(receipt -> invocation.<String>getArgument(0)
                                .equals(receipt.getClientRequestId())));
        when(repository.findByReceiptIdForUpdate(any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get())
                        .filter(receipt -> invocation.<String>getArgument(0)
                                .equals(receipt.getReceiptId())));
        when(repository.findByEffectAttemptId(any()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get())
                        .filter(receipt -> invocation.<String>getArgument(0)
                                .equals(receipt.getEffectAttemptId())));
        return repository;
    }

    private void stubProviderEffect(
            Fixture fixture,
            DispatchTaskDTO task,
            AtomicInteger providerEffects) {
        when(fixture.dispatchFacade.createTask(any(), any(), eq(fixture.plan), any()))
                .thenAnswer(invocation -> {
                    AgentResolveContext context = invocation.getArgument(1);
                    TaskCreateCommandCoordinator.ProviderEffectGate gate =
                            invocation.getArgument(3);
                    TaskCreateCommandCoordinator.ProviderEffectIdentity identity =
                            new TaskCreateCommandCoordinator.ProviderEffectIdentity(
                                    fixture.plan.executionRoute(),
                                    context.getTenantId(),
                                    context.getUserId(),
                                    fixture.plan.logicalAgentId(),
                                    fixture.plan.providerType(),
                                    fixture.plan.physicalWorkerId(),
                                    fixture.plan.modelConfigId(),
                                    fixture.plan.model(),
                                    fixture.plan.sessionId(),
                                    fixture.plan.directoryId());
                    return gate.invokePrepared(
                            fixture.plan,
                            () -> identity,
                            () -> TaskCreateCommandCoordinator.PreparedProviderEffect.capture(
                                    identity,
                                    task,
                                    captured -> {
                                        providerEffects.incrementAndGet();
                                        return captured;
                                    }));
                });
    }

    private TaskDispatchRequest canonicalRequest(AgentTaskSubmitRequest request) {
        return TaskDispatchRequest.builder()
                .agentId(request.getAgentId())
                .providerType(request.getProviderType())
                .sessionId("session-1")
                .workerId(request.getWorkerId())
                .prompt(request.getPrompt())
                .cwd(request.getCwd())
                .directoryId(request.getDirectoryId())
                .model(request.getModel())
                .modelConfigId(request.getModelConfigId())
                .maxTurns(request.getMaxTurns())
                .contextId(request.getContextId())
                .metadata(request.getMetadata() == null
                        ? null
                        : new LinkedHashMap<>(request.getMetadata()))
                .build();
    }

    private OpenApiRuntimeTaskCreateFacade.VerifiedCreateCommand command(
            OpenApiRuntimeTaskCreateFacade facade,
            OpenApiRuntimeTaskLaunchPlanner.LaunchPlan launchPlan) {
        OpenApiRuntimeTaskCreateFacade.PrepareOutcome preparation = facade.prepare(
                new OpenApiRuntimeTaskCreateFacade.VerifiedCreateContext(
                        new OpenApiRuntimeTaskCreateFacade.RuntimeCredentialReference(
                                "tenant-1", "app-1", "credential-1", "access-token-id-1"),
                        "upstream-1",
                        "agent-1",
                        "skill-1",
                        "ctx-1",
                        false,
                        "model-1",
                        new RuntimeRequestAuditService.AuditHandle(REQUEST_ID)));
        assertTrue(preparation.ready());
        return new OpenApiRuntimeTaskCreateFacade.VerifiedCreateCommand(
                preparation.preparedContext(),
                "create task",
                3,
                "{\"trace\":\"composition\"}",
                launchPlan);
    }

    private OpenApiRuntimeTaskLaunchPlanner.LaunchPlan launchPlan() {
        OpenApiRuntimeTaskLaunchPlanner.LaunchContext context =
                new OpenApiRuntimeTaskLaunchPlanner.LaunchContext(
                        "tenant-1", "app-1", "upstream-1", "agent-1", "skill-1", "ctx-1");
        A2AgentResourceResolver.ResolvedModelResource model =
                new A2AgentResourceResolver.ResolvedModelResource(
                        "model-1",
                        "model-1",
                        null,
                        LlmModelCategory.GENERAL,
                        "model-variant-1",
                        "REQUESTED_MODEL",
                        "LANGGRAPH_BIZ",
                        "MODEL_CONFIG_GRANT");
        OpenApiRuntimeTaskLaunchPlanner.WorkerSelection selection =
                new OpenApiRuntimeTaskLaunchPlanner.WorkerSelection(
                        context,
                        "pool-1",
                        ResourceOwnerType.CLIENT_APP,
                        "app-1",
                        "worker-1",
                        "LANGGRAPH_BIZ",
                        "model-1",
                        "model-variant-1",
                        "directory-1",
                        "/workspace",
                        List.of("/workspace"),
                        List.of(),
                        true,
                        List.of());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("providerType", "LANGGRAPH_BIZ");
        metadata.put("modelConfigId", "model-1");
        metadata.put("workerId", "worker-1");
        metadata.put("workerBackend", "LANGGRAPH_BIZ");
        metadata.put("requestedToolCount", 0);
        metadata.put("effectiveToolCount", 0);
        metadata.put("toolScopeKind", "NAVIGATOR_BUSINESS_MCP_WRAPPERS");
        metadata.put("toolScopeSource", "REQUEST_EXPLICIT_EMPTY");
        metadata.put("requestedFunctionCount", 0);
        metadata.put("functionScopeSource", "REQUEST_EXPLICIT_EMPTY");
        metadata.put("runtimeContext", new LinkedHashMap<>(Map.of("existing", "sentinel")));
        return new OpenApiRuntimeTaskLaunchPlanner.LaunchPlan(
                context,
                null,
                model,
                null,
                false,
                metadata,
                List.of(),
                selection);
    }

    private DispatchTaskDTO exactTask(String taskId, String status) {
        return DispatchTaskDTO.builder()
                .taskId(taskId)
                .sessionId("session-1")
                .contextId("ctx-1")
                .workerId("worker-1")
                .agentId("agent-1")
                .providerType("LANGGRAPH_BIZ")
                .status(status)
                .model("model-variant-1")
                .modelConfigId("model-1")
                .directoryId("directory-1")
                .build();
    }

    private A2aTask a2aTask(String taskId, A2aTaskState state) {
        return A2aTask.builder()
                .id(taskId)
                .contextId("ctx-1")
                .metadata(Map.of("sessionId", "session-1", "workerId", "worker-1"))
                .status(A2aTaskStatus.builder().state(state).build())
                .build();
    }

    private BusinessAgentTaskService.PreparedOpenApiTaskScopedToken preparedToken() {
        return new BusinessAgentTaskService.PreparedOpenApiTaskScopedToken(
                "task-token-1",
                "token-id-1",
                "worker-1",
                "lease-1",
                "pool-1",
                "LANGGRAPH_BIZ",
                0,
                "REQUEST_EXPLICIT_EMPTY",
                true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private record Fixture(
            OpenApiRuntimeTaskCreateFacade facade,
            TaskDispatchFacade dispatchFacade,
            CommandOnceReceiptService receiptService,
            CommandOnceReceiptRepository receiptRepository,
            BusinessAgentTaskService taskService,
            RuntimeRequestAuditService auditService,
            OpenApiSessionQueryService sessionQueryService,
            BusinessAgentSessionService sessionService,
            BusinessAgentTaskService.OpenApiTaskWorkerPreflight preflight,
            TaskCreateTargetResolver.CreateExecutionPlan plan) {
    }
}
