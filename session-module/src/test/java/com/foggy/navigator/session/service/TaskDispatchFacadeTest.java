package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aContext;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.NativeSubtaskStateRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.session.exception.SessionProviderBoundMismatchException;
import com.foggy.navigator.session.agent.ContextResolvingA2aAgent;
import com.foggy.navigator.session.lifecycle.LifecycleIngressGate;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.AgentConversationContextRepository;
import com.foggy.navigator.session.repository.SessionCodingAgentRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggy.navigator.spi.agent.InternalTaskDispatchMarkers;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.WorkerSessionMessage;
import com.foggy.navigator.spi.agent.WorkerSessionMessageCount;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import com.foggy.navigator.spi.agent.WorkerSessionSummary;
import com.foggy.navigator.spi.agent.WorkerSessionSyncResult;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.CanonicalCommandReceiptPort;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataIntegrityViolationException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskDispatchFacadeTest {

    private static final String TERMINATION_REQUEST_ID =
            "550e8400-e29b-41d4-a716-446655440001";

    @Mock
    private UnifiedAgentResolver agentResolver;
    @Mock
    private SessionBindingService bindingService;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private SessionTaskResourceAccessService resourceAccessService;
    @Mock
    private A2aAgent agent;
    @Mock
    private TypedTaskProvider taskQueryProvider;
    @Mock
    private LlmModelManager llmModelManager;
    @Mock
    private SessionTaskRepository sessionTaskRepository;
    @Mock
    private NativeSubtaskStateRepository nativeSubtaskStateRepository;
    @Mock
    private WorkingDirectoryRepository workingDirectoryRepository;
    @Mock
    private SessionCodingAgentRepository sessionCodingAgentRepository;
    @Mock
    private WorkerManagementFacade workerManagementFacade;
    @Mock
    private AgentConversationContextRepository agentConversationContextRepository;
    @Mock
    private AgentContextStore agentContextStore;
    @Mock
    private TaskLookupProvider codexSdkTaskLookupProvider;
    @Mock
    private TaskLookupProvider codexBizTaskLookupProvider;

    private TaskDispatchFacade facade;

    @BeforeEach
    void setUp() {
        facade = createFacade(List.of(taskQueryProvider));
    }

    private TaskDispatchFacade createFacade(List<? extends TypedTaskProvider> providers) {
        TaskDispatchFacade created = new TaskDispatchFacade(
                agentResolver,
                bindingService,
                sessionRepository,
                resourceAccessService,
                providers,
                providers,
                providers,
                providers,
                llmModelManager);
        ReflectionTestUtils.setField(created, "agentContextStore", agentContextStore);
        ReflectionTestUtils.setField(created, "sessionCodingAgentRepository", sessionCodingAgentRepository);
        ReflectionTestUtils.setField(created, "workerManagementFacade", workerManagementFacade);
        return created;
    }

    @SuppressWarnings("unchecked")
    private TaskCreateCommandCoordinator.ProviderEffectGate passThroughGate() {
        TaskCreateCommandCoordinator.ProviderEffectGate gate =
                mock(TaskCreateCommandCoordinator.ProviderEffectGate.class);
        lenient().doAnswer(invocation ->
                        ((Supplier<Object>) invocation.getArgument(2)).get())
                .when(gate).invoke(any(), any(), any());
        lenient().doAnswer(invocation -> {
                    Supplier<TaskCreateCommandCoordinator.ProviderEffectIdentity> identitySupplier =
                            invocation.getArgument(1);
                    Runnable routePreparation = invocation.getArgument(2);
                    Supplier<TaskCreateCommandCoordinator.PreparedProviderEffect<Object>>
                            preparedEffectSupplier = invocation.getArgument(3);
                    identitySupplier.get();
                    routePreparation.run();
                    identitySupplier.get();
                    return preparedEffectSupplier.get().execute();
                })
                .when(gate).invokePrepared(any(), any(), any(), any());
        lenient().when(gate.providerEffectPermitted()).thenReturn(true);
        return gate;
    }

    private TaskCreateTargetResolver.CreateExecutionPlan guardedPlan(
            String logicalAgentId,
            String providerType,
            TaskCreateTargetResolver.ExecutionRoute executionRoute) {
        return guardedPlan(
                logicalAgentId, providerType, executionRoute, "session-1");
    }

    private TaskCreateTargetResolver.CreateExecutionPlan guardedPlan(
            String logicalAgentId,
            String providerType,
            TaskCreateTargetResolver.ExecutionRoute executionRoute,
            String sessionId) {
        TaskCreateTargetResolver.CreateExecutionPlan plan =
                mock(TaskCreateTargetResolver.CreateExecutionPlan.class);
        lenient().when(plan.ownerUserId()).thenReturn("user-1");
        lenient().when(plan.logicalAgentId()).thenReturn(logicalAgentId);
        lenient().when(plan.providerType()).thenReturn(providerType);
        lenient().when(plan.physicalWorkerId()).thenReturn("worker-1");
        lenient().when(plan.sessionId()).thenReturn(sessionId);
        lenient().when(plan.executionRoute()).thenReturn(executionRoute);
        lenient().when(plan.directProviderRoute())
                .thenReturn(executionRoute == TaskCreateTargetResolver.ExecutionRoute.DIRECT);
        if (executionRoute == TaskCreateTargetResolver.ExecutionRoute.A2A) {
            lenient().when(plan.agentLookup()).thenReturn(new TaskCreateTargetResolver.AgentLookup(
                    logicalAgentId, "AGENT_ID", "UI"));
        }
        return plan;
    }

    private TaskTerminationCommandCoordinator.TerminationCommandResult
    executeCanonicalTermination(
            TaskTerminationCommandCoordinator.TerminationExecutionPlan plan) {
        CanonicalCommandReceiptPort receipts = mock(
                CanonicalCommandReceiptPort.class,
                withSettings().lenient());
        TaskTerminationCommandCoordinator.PlanBinding planBinding =
                TaskTerminationCommandCoordinator.PlanBinding.from(plan);
        CanonicalCommandEnvelope.CommandBinding binding =
                new CanonicalCommandEnvelope.CommandBinding(
                        CanonicalCommandEnvelope.CommandKind.TERMINATE,
                        new CanonicalCommandEnvelope.Ingress(
                                CanonicalCommandEnvelope.CommandIngress.DIRECT,
                                "NAVIGATOR_UI",
                                "/api/v1/tasks/{taskId}/cancel"),
                        new CanonicalCommandEnvelope.Request(
                                TERMINATION_REQUEST_ID,
                                TERMINATION_REQUEST_ID,
                                TERMINATION_REQUEST_ID),
                        new CanonicalCommandEnvelope.Actor(
                                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                                AuthorizationPrincipalType.NAVIGATOR_USER,
                                AuthorizationCredentialLane.NAVIGATOR_JWT,
                                "principal-fingerprint",
                                null),
                        new CanonicalCommandEnvelope.Ownership(
                                planBinding.tenantReference(), "user-1", null, null),
                        planBinding.target(),
                        planBinding.effect());
        VerifiedCommandAuthorizationDecision.ServerAuthority authority =
                new VerifiedCommandAuthorizationDecision.ServerAuthority(
                        "test.policy.v1",
                        Clock.fixed(
                                Instant.parse("2026-08-04T00:00:00Z"),
                                ZoneOffset.UTC),
                        Duration.ofMinutes(5));
        VerifiedCommandAuthorizationDecision decision = authority.issue(binding);
        CanonicalCommandEnvelope envelope = new CanonicalCommandEnvelope(
                CanonicalCommandEnvelope.SCHEMA_VERSION,
                binding,
                decision.metadata());
        CanonicalCommandReceiptPort.ReceiptSnapshot prepared =
                terminationReceipt(
                        CanonicalCommandReceiptPort.ReceiptState.PREPARED,
                        null,
                        null,
                        null);
        CanonicalCommandReceiptPort.ReceiptSnapshot permitted =
                terminationReceipt(
                        CanonicalCommandReceiptPort.ReceiptState.EFFECT_STARTED,
                        "attempt-1",
                        null,
                        null);
        lenient().when(receipts.prepare(envelope, decision)).thenReturn(
                new CanonicalCommandReceiptPort.PrepareResult(
                        CanonicalCommandReceiptPort.PrepareDisposition.CREATED,
                        prepared));
        lenient().when(receipts.beginEffect(envelope, decision)).thenReturn(
                new CanonicalCommandReceiptPort.EffectPermit(
                        CanonicalCommandReceiptPort.BeginEffectDisposition.PERMITTED,
                        permitted));
        lenient().when(receipts.recordResult(
                        eq(TERMINATION_REQUEST_ID),
                        eq("attempt-1"),
                        anyString(),
                        anyString()))
                .thenAnswer(invocation -> terminationReceipt(
                        CanonicalCommandReceiptPort.ReceiptState.RESULT_RECORDED,
                        "attempt-1",
                        invocation.getArgument(2),
                        invocation.getArgument(3)));
        return new TaskTerminationCommandCoordinator(facade, receipts)
                .execute(plan, envelope, decision);
    }

    private CanonicalCommandReceiptPort.ReceiptSnapshot terminationReceipt(
            CanonicalCommandReceiptPort.ReceiptState state,
            String attemptId,
            String reference,
            String safeCode) {
        return new CanonicalCommandReceiptPort.ReceiptSnapshot(
                "receipt-1",
                TERMINATION_REQUEST_ID,
                state,
                attemptId,
                reference,
                safeCode,
                "decision-1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0L);
    }

    private CodingAgentEntity ownedAgent(String agentId,
                                         String userId,
                                         String tenantId,
                                         String agentType,
                                         String workerId) {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId(agentId);
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setAgentType(agentType);
        entity.setWorkerId(workerId);
        entity.setEnabled(true);
        return entity;
    }

    private TaskCreateContextNormalizer newContextNormalizer(EntityManager entityManager) {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        lenient().when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        lenient().when(taskQueryProvider.getProviderType())
                .thenReturn(ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER);
        lenient().when(taskQueryProvider.listTasksBySession(anyString()))
                .thenReturn(List.of());
        lenient().when(codexSdkTaskLookupProvider.getProviderType())
                .thenReturn(ProviderRouteRegistry.PROVIDER_CODEX_WORKER);
        lenient().when(codexSdkTaskLookupProvider.listTasksBySession(anyString()))
                .thenReturn(List.of());
        lenient().when(codexBizTaskLookupProvider.getProviderType())
                .thenReturn(ProviderRouteRegistry.PROVIDER_CODEX_BIZ_WORKER);
        lenient().when(codexBizTaskLookupProvider.listTasksBySession(anyString()))
                .thenReturn(List.of());
        lenient().when(sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of());
        TaskCreateContextNormalizer normalizer = new TaskCreateContextNormalizer(
                agentConversationContextRepository,
                sessionRepository,
                sessionTaskRepository,
                sessionCodingAgentRepository,
                resourceAccessService,
                List.of(taskQueryProvider, codexSdkTaskLookupProvider,
                        codexBizTaskLookupProvider),
                manager);
        ReflectionTestUtils.setField(normalizer, "entityManager", entityManager);
        return normalizer;
    }

    private TaskCreateTargetResolver.CreateExecutionPlan normalizerPlan(
            String ownerUserId,
            String tenantId,
            String logicalAgentId,
            String providerType,
            String physicalWorkerId,
            String sessionId) {
        TaskCreateTargetResolver.CreateExecutionPlan plan =
                mock(TaskCreateTargetResolver.CreateExecutionPlan.class);
        lenient().when(plan.executionRoute()).thenReturn(TaskCreateTargetResolver.ExecutionRoute.A2A);
        lenient().when(plan.ownerUserId()).thenReturn(ownerUserId);
        lenient().when(plan.tenantId()).thenReturn(tenantId);
        lenient().when(plan.logicalAgentId()).thenReturn(logicalAgentId);
        lenient().when(plan.providerType()).thenReturn(providerType);
        lenient().when(plan.physicalWorkerId()).thenReturn(physicalWorkerId);
        lenient().when(plan.sessionId()).thenReturn(sessionId);
        return plan;
    }

    private SessionEntity canonicalSession(
            String sessionId,
            String ownerUserId,
            String tenantId,
            String logicalAgentId,
            String providerType,
            String physicalWorkerId) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(ownerUserId);
        session.setTenantId(tenantId);
        session.setAgentId(logicalAgentId);
        session.setProviderType(providerType);
        session.setCurrentWorkerId(physicalWorkerId);
        session.setStatus("ACTIVE");
        return session;
    }

    private TaskCreateContextNormalizer.CanonicalContextProof existingContextProof(
            EntityManager entityManager,
            String contextId,
            String sessionId,
            String agentId,
            String providerType,
            String physicalWorkerId,
            String agentType) {
        String userId = "user-proof";
        String tenantId = "tenant-proof";
        CodingAgentEntity agentEntity = ownedAgent(
                agentId, userId, tenantId, agentType, physicalWorkerId);
        SessionEntity session = canonicalSession(
                sessionId, userId, tenantId, agentId, providerType,
                ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER.equals(providerType)
                        ? null : physicalWorkerId);
        AgentConversationContextEntity stored = new AgentConversationContextEntity();
        stored.setContextId(contextId);
        stored.setUserId(userId);
        stored.setTargetAgentId(agentId);
        stored.setAgentType(providerType);
        stored.setNavigatorSessionId(sessionId);
        when(agentConversationContextRepository.findById(contextId)).thenReturn(Optional.of(stored));
        when(sessionCodingAgentRepository.findByAgentIdAndUserId(agentId, userId))
                .thenReturn(Optional.of(agentEntity));
        when(resourceAccessService.requireOwnedSession(sessionId, userId, tenantId))
                .thenReturn(session);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId(contextId)
                .agentId(agentId)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .requestSource("UI")
                .build();
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);
        TaskCreateContextNormalizer.Inspection inspection =
                normalizer.inspect(request, context, false);
        assertNotNull(inspection);
        inspection.applyForResolution(request, context);
        TaskCreateTargetResolver.CreateExecutionPlan plan = normalizerPlan(
                userId, tenantId, agentId, providerType, physicalWorkerId, sessionId);
        return normalizer.sealForResolution(inspection, plan);
    }

    @Test
    void createTask_reusesSessionBoundAgentWhenAgentIdOmitted() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .workerId("worker-1")
                .prompt("hi")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setAgentId("agent-2");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        when(agentResolver.resolveAgent(eq("agent-2"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-2"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-2").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("agent-2", result.getAgentId());
        verify(agentResolver).resolveAgent(eq("agent-2"), any());
        verify(bindingService).getOrBind("session-1", "agent-2", "claude-worker", "SESSION_AGENT");
    }

    @Test
    void createTaskConsumesTheSameResolvedPlanBeforeA2aEffect() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("agent-plan-1")
                .workerId("worker-1")
                .prompt("planned a2a")
                .metadata(Map.ofEntries(
                        Map.entry("agentId", "agent-injected"),
                        Map.entry("providerType", "provider-injected"),
                        Map.entry("sessionId", "session-injected"),
                        Map.entry("contextId", "context-injected"),
                        Map.entry("workerId", "worker-injected"),
                        Map.entry("directoryId", "directory-injected"),
                        Map.entry("model", "model-injected"),
                        Map.entry("modelConfigId", "config-injected"),
                        Map.entry("userId", "user-injected"),
                        Map.entry("tenantId", "tenant-injected"),
                        Map.entry("trace", "trace-ok")))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();
        when(sessionCodingAgentRepository.findByAgentIdAndUserId("agent-plan-1", "user-1"))
                .thenReturn(Optional.of(ownedAgent(
                        "agent-plan-1", "user-1", null,
                        "LOCAL_CLAUDE_WORKER", "worker-1")));

        TaskCreateTargetResolver.CreateExecutionPlan plan =
                facade.resolveCreateExecutionPlan(request, context);
        assertEquals(TaskCreateTargetResolver.ExecutionRoute.A2A, plan.executionRoute());
        assertNull(request.getProviderType(), "resolution must not rewrite the request");

        when(agentResolver.resolveAgentByProviderTypeExact(
                eq("claude-worker"), eq("agent-plan-1"), any())).thenReturn(Optional.of(agent));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-plan-1").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-plan-a2a-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        DispatchTaskDTO result = facade.createTask(request, context, plan, passThroughGate());

        assertEquals("task-plan-a2a-1", result.getTaskId());
        assertEquals("claude-worker", request.getProviderType());
        assertEquals("worker-1", request.getWorkerId());
        verify(agent).sendTask(argThat(message -> {
            Map<String, Object> metadata = message.getMetadata();
            return "agent-plan-1".equals(metadata.get("agentId"))
                    && "claude-worker".equals(metadata.get("providerType"))
                    && "worker-1".equals(metadata.get("workerId"))
                    && "trace-ok".equals(metadata.get("trace"))
                    && !metadata.containsKey("sessionId")
                    && !metadata.containsKey("contextId")
                    && !metadata.containsKey("directoryId")
                    && !metadata.containsKey("model")
                    && !metadata.containsKey("modelConfigId")
                    && !metadata.containsKey("userId")
                    && !metadata.containsKey("tenantId");
        }));
        verify(agentResolver).resolveAgentByProviderTypeExact(
                eq("claude-worker"), eq("agent-plan-1"), any());
        verify(agentResolver, never()).resolveAgent(any(), any());
        verify(agentResolver, never()).getProviderType(any(), any());
    }

    @Test
    void createTaskConsumesTheSameResolvedPlanBeforeDirectEffect() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-worker")
                .workerId("worker-1")
                .prompt("planned direct")
                .metadata(Map.ofEntries(
                        Map.entry("agentId", "agent-injected"),
                        Map.entry("providerType", "provider-injected"),
                        Map.entry("sessionId", "session-injected"),
                        Map.entry("contextId", "context-injected"),
                        Map.entry("workerId", "worker-injected"),
                        Map.entry("directoryId", "directory-injected"),
                        Map.entry("model", "model-injected"),
                        Map.entry("modelConfigId", "config-injected"),
                        Map.entry("userId", "user-injected"),
                        Map.entry("tenantId", "tenant-injected"),
                        Map.entry("trace", "trace-ok")))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(DispatchTaskDTO.builder()
                        .taskId("task-plan-direct-1")
                        .providerType("codex-worker")
                        .workerId("worker-1")
                        .build());

        TaskCreateTargetResolver.CreateExecutionPlan plan =
                facade.resolveCreateExecutionPlan(request, context);
        assertEquals(TaskCreateTargetResolver.ExecutionRoute.DIRECT, plan.executionRoute());

        DispatchTaskDTO result = facade.createTask(request, context, plan, passThroughGate());

        assertEquals("task-plan-direct-1", result.getTaskId());
        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(taskQueryProvider).createTaskDirect(
                argThat(params -> "codex-worker".equals(params.get("providerType"))
                        && "worker-1".equals(params.get("workerId"))
                        && "trace-ok".equals(params.get("trace"))
                        && !params.containsKey("agentId")
                        && !params.containsKey("sessionId")
                        && !params.containsKey("contextId")
                        && !params.containsKey("directoryId")
                        && !params.containsKey("model")
                        && !params.containsKey("modelConfigId")
                        && !params.containsKey("userId")
                        && !params.containsKey("tenantId")),
                eq("user-1"), eq("tenant-1"));
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTaskRejectsPlanDriftWithZeroProviderEffect() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-worker")
                .workerId("worker-1")
                .prompt("must not dispatch")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        TaskCreateTargetResolver.CreateExecutionPlan plan =
                facade.resolveCreateExecutionPlan(request, context);
        request.setWorkerId("worker-2");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context, plan, passThroughGate()));

        assertTrue(error.getMessage().contains("workerId"));
        request.setWorkerId("worker-1");
        context.setUserId(null);
        assertThrows(SecurityException.class,
                () -> facade.createTask(request, context, plan, passThroughGate()));
        context.setUserId("user-1");
        request.setContextAlias("alias-drift");
        IllegalArgumentException aliasDrift = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context, plan, passThroughGate()));
        assertTrue(aliasDrift.getMessage().contains("contextAlias"));
        request.setContextAlias(null);
        request.setResume(true);
        IllegalArgumentException resumeDrift = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context, plan, passThroughGate()));
        assertTrue(resumeDrift.getMessage().contains("resume continuation"));
        request.setResume(false);

        TaskDispatchRequest a2aRequest = TaskDispatchRequest.builder()
                .agentId("agent-source-1")
                .workerId("worker-1")
                .build();
        AgentResolveContext a2aContext = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();
        when(sessionCodingAgentRepository.findByAgentIdAndUserId("agent-source-1", "user-1"))
                .thenReturn(Optional.of(ownedAgent(
                        "agent-source-1", "user-1", null,
                        "LOCAL_CLAUDE_WORKER", "worker-1")));
        TaskCreateTargetResolver.CreateExecutionPlan a2aPlan =
                facade.resolveCreateExecutionPlan(a2aRequest, a2aContext);
        a2aContext.setRequestSource("OPEN_API");
        assertThrows(SecurityException.class,
                () -> facade.createTask(
                        a2aRequest, a2aContext, a2aPlan, passThroughGate()));
        a2aContext.setRequestSource("UI");
        when(agentResolver.resolveAgentByProviderTypeExact(
                eq("claude-worker"), eq("agent-source-1"), any())).thenReturn(Optional.of(agent));
        IllegalArgumentException missingCard = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(
                        a2aRequest, a2aContext, a2aPlan, passThroughGate()));
        assertTrue(missingCard.getMessage().contains("card id"));
        when(agent.getAgentCard()).thenReturn(
                A2aAgentCard.builder().id(" agent-source-1 ").build());
        IllegalArgumentException paddedCard = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(
                        a2aRequest, a2aContext, a2aPlan, passThroughGate()));
        assertTrue(paddedCard.getMessage().contains("conflicts with resolved"));

        verify(taskQueryProvider, never()).createTaskDirect(any(), any(), any());
        verify(agentResolver, never()).resolveAgent(any(), any());
        verify(agentResolver, never()).getProviderType(any(), any());
        verify(agent, never()).sendTask(any());
    }

    @Test
    void guardedA2aOrdersLifecycleReceiptProviderAndConfirm() {
        LifecycleIngressGate lifecycleGate = mock(LifecycleIngressGate.class);
        LifecycleIngressGate.IngressPermit ingressPermit =
                mock(LifecycleIngressGate.IngressPermit.class);
        TaskCreateContextNormalizer normalizer = mock(TaskCreateContextNormalizer.class);
        TaskCreateContextNormalizer.PendingContextClaim pendingClaim =
                mock(TaskCreateContextNormalizer.PendingContextClaim.class);
        TaskCreateContextNormalizer.CanonicalContextProof contextProof =
                mock(TaskCreateContextNormalizer.CanonicalContextProof.class);
        ReflectionTestUtils.setField(facade, "lifecycleIngressGate", lifecycleGate);
        ReflectionTestUtils.setField(facade, "taskCreateContextNormalizer", normalizer);
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("agent-gated-a2a-1")
                .providerType("claude-worker")
                .workerId("worker-1")
                .sessionId("session-1")
                .contextId("ctx-gated-a2a-1")
                .prompt("gated a2a")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = guardedPlan(
                "agent-gated-a2a-1", "claude-worker",
                TaskCreateTargetResolver.ExecutionRoute.A2A);
        when(plan.pendingContextClaim()).thenReturn(pendingClaim);
        TaskCreateCommandCoordinator.ProviderEffectGate effectGate = passThroughGate();
        when(normalizer.claimPendingAfterPermit(pendingClaim, plan))
                .thenReturn(contextProof);
        when(lifecycleGate.reserveBeforeEffect("session-1", "worker-1"))
                .thenReturn(ingressPermit);
        when(agentResolver.resolveAgentByProviderTypeExact(
                eq("claude-worker"), eq("agent-gated-a2a-1"), any()))
                .thenReturn(Optional.of(agent));
        when(agent.getAgentCard()).thenReturn(
                A2aAgentCard.builder().id("agent-gated-a2a-1").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-gated-a2a-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());
        SessionTaskEntity storedTask = new SessionTaskEntity();
        storedTask.setTaskId("task-gated-a2a-1");
        storedTask.setProviderType("claude-worker");
        when(sessionTaskRepository.findByTaskIdForUpdate("task-gated-a2a-1"))
                .thenReturn(Optional.of(storedTask));

        DispatchTaskDTO result = facade.createTask(
                request, context, plan, effectGate);

        assertEquals("task-gated-a2a-1", result.getTaskId());
        InOrder ordered = inOrder(
                effectGate,
                normalizer,
                bindingService,
                lifecycleGate,
                agent,
                sessionTaskRepository);
        ordered.verify(effectGate).invokePrepared(
                eq(plan),
                any(),
                any(),
                any());
        ordered.verify(normalizer).claimPendingAfterPermit(pendingClaim, plan);
        ordered.verify(bindingService).getOrBind(
                "session-1", "agent-gated-a2a-1", "claude-worker", "AGENT_ID");
        ordered.verify(lifecycleGate).reserveBeforeEffect("session-1", "worker-1");
        ordered.verify(agent).sendTask(any());
        ordered.verify(sessionTaskRepository).findByTaskIdForUpdate("task-gated-a2a-1");
        ordered.verify(sessionTaskRepository).save(storedTask);
        ordered.verify(normalizer).completeAgentSessionRef(
                eq(contextProof), eq("task-gated-a2a-1"), isNull());
        ordered.verify(lifecycleGate).confirm(ingressPermit, "task-gated-a2a-1");
        verify(lifecycleGate, never()).releaseFailed(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void guardedA2aCapturedPayloadAndPersistenceIgnoreMutableRequestDrift() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        Map<String, Object> nestedMetadata = new LinkedHashMap<>();
        nestedMetadata.put("value", "metadata-original");
        Map<String, Object> nestedContext = new LinkedHashMap<>();
        nestedContext.put("value", "context-original");
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("value", "attachment-original");
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("agent-snapshot-a2a-1")
                .providerType("claude-worker")
                .workerId("worker-1")
                .model("model-original")
                .prompt("snapshot payload")
                .metadata(Map.of("nested", nestedMetadata))
                .context(Map.of("nested", nestedContext))
                .attachments(List.of(attachment))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = guardedPlan(
                "agent-snapshot-a2a-1",
                "claude-worker",
                TaskCreateTargetResolver.ExecutionRoute.A2A,
                null);
        when(agentResolver.resolveAgentByProviderTypeExact(
                eq("claude-worker"), eq("agent-snapshot-a2a-1"), any()))
                .thenReturn(Optional.of(agent));
        when(agent.getAgentCard()).thenReturn(
                A2aAgentCard.builder().id("agent-snapshot-a2a-1").build());
        when(agent.sendTask(any())).thenAnswer(invocation -> {
            A2aMessage message = invocation.getArgument(0);
            nestedMetadata.put("value", "metadata-caller-drift");
            nestedContext.put("value", "context-caller-drift");
            attachment.put("value", "attachment-caller-drift");
            request.setModel("model-caller-drift");

            Map<String, Object> messageNested = assertInstanceOf(
                    Map.class, message.getMetadata().get("nested"));
            Map<String, Object> messageContext = assertInstanceOf(
                    Map.class, message.getMetadata().get("context"));
            List<Map<String, Object>> messageAttachments = assertInstanceOf(
                    List.class, message.getMetadata().get("attachments"));
            assertEquals("metadata-original", messageNested.get("value"));
            assertEquals("context-original",
                    assertInstanceOf(Map.class, messageContext.get("nested")).get("value"));
            assertEquals("attachment-original", messageAttachments.get(0).get("value"));

            messageNested.put("value", "metadata-provider-drift");
            ((Map<String, Object>) messageContext.get("nested"))
                    .put("value", "context-provider-drift");
            messageAttachments.get(0).put("value", "attachment-provider-drift");
            return A2aTask.builder()
                    .id("task-snapshot-a2a-1")
                    .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                    .build();
        });
        SessionTaskEntity storedTask = new SessionTaskEntity();
        storedTask.setTaskId("task-snapshot-a2a-1");
        storedTask.setProviderType("claude-worker");
        when(sessionTaskRepository.findByTaskIdForUpdate("task-snapshot-a2a-1"))
                .thenReturn(Optional.of(storedTask));

        DispatchTaskDTO result = facade.createTask(
                request, context, plan, passThroughGate());

        assertEquals("task-snapshot-a2a-1", result.getTaskId());
        assertEquals("model-original", storedTask.getModel());
        assertEquals("model-caller-drift", request.getModel());
        assertEquals("metadata-caller-drift", nestedMetadata.get("value"));
        assertEquals("context-caller-drift", nestedContext.get("value"));
        assertEquals("attachment-caller-drift", attachment.get("value"));
        verify(sessionTaskRepository).save(storedTask);
    }

    @Test
    void guardedDirectOrdersLifecycleReceiptProviderAndConfirm() {
        LifecycleIngressGate lifecycleGate = mock(LifecycleIngressGate.class);
        LifecycleIngressGate.IngressPermit ingressPermit =
                mock(LifecycleIngressGate.IngressPermit.class);
        ReflectionTestUtils.setField(facade, "lifecycleIngressGate", lifecycleGate);
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(
                facade, "agentConversationContextRepository", agentConversationContextRepository);
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("agent-direct-1")
                .providerType("codex-worker")
                .workerId("worker-1")
                .sessionId("session-1")
                .contextId("ctx-direct-1")
                .model("model-direct-1")
                .prompt("gated direct")
                .initializeRuntimeAffinity(true)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = guardedPlan(
                "agent-direct-1", "codex-worker",
                TaskCreateTargetResolver.ExecutionRoute.DIRECT);
        TaskCreateCommandCoordinator.ProviderEffectGate effectGate = passThroughGate();
        when(lifecycleGate.reserveBeforeEffect("session-1", "worker-1"))
                .thenReturn(ingressPermit);
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), isNull()))
                .thenReturn(DispatchTaskDTO.builder()
                        .taskId("task-gated-direct-1")
                        .providerType("codex-worker")
                        .workerId("worker-1")
                        .sessionId("session-1")
                        .contextId("ctx-direct-1")
                        .build());
        SessionTaskEntity storedTask = new SessionTaskEntity();
        storedTask.setTaskId("task-gated-direct-1");
        storedTask.setProviderType("codex-worker");
        when(sessionTaskRepository.findByTaskIdForUpdate("task-gated-direct-1"))
                .thenReturn(Optional.of(storedTask));
        clearInvocations(taskQueryProvider);

        DispatchTaskDTO result = facade.createTask(
                request, context, plan, effectGate);

        assertEquals("task-gated-direct-1", result.getTaskId());
        assertFalse(InternalTaskDispatchMarkers.requestsRuntimeAffinityInitialization(
                request.getMetadata()));
        InOrder ordered = inOrder(
                effectGate,
                lifecycleGate,
                taskQueryProvider,
                sessionTaskRepository,
                resourceAccessService,
                agentContextStore);
        ordered.verify(effectGate).invokePrepared(
                eq(plan),
                any(),
                any(),
                any());
        ordered.verify(lifecycleGate).reserveBeforeEffect("session-1", "worker-1");
        ordered.verify(taskQueryProvider).createTaskDirect(
                argThat(InternalTaskDispatchMarkers::requestsRuntimeAffinityInitialization),
                eq("user-1"), isNull());
        ordered.verify(sessionTaskRepository).findByTaskIdForUpdate("task-gated-direct-1");
        ordered.verify(sessionTaskRepository).save(storedTask);
        ordered.verify(resourceAccessService).requireOwnedSession(
                "session-1", "user-1", null);
        ordered.verify(agentContextStore).saveSessionRefFull(
                eq("ctx-direct-1"),
                eq("codex-worker"),
                isNull(),
                eq("session-1"),
                eq("user-1"),
                eq("agent-direct-1"),
                isNull());
        ordered.verify(lifecycleGate).confirm(ingressPermit, "task-gated-direct-1");
        assertEquals("model-direct-1", storedTask.getModel());
        verify(lifecycleGate, never()).releaseFailed(any());
        verify(effectGate, never()).invoke(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void guardedDirectCapturedParamsAndPersistenceIgnoreMutableDrift() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        Map<String, Object> nestedMetadata = new LinkedHashMap<>();
        nestedMetadata.put("value", "metadata-before-participant");
        Map<String, Object> nestedContext = new LinkedHashMap<>();
        nestedContext.put("value", "context-before-participant");
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("value", "attachment-before-participant");
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-worker")
                .workerId("worker-1")
                .model("model-before-participant")
                .metadata(Map.of("nested", nestedMetadata))
                .context(Map.of("nested", nestedContext))
                .attachments(List.of(attachment))
                .initializeRuntimeAffinity(true)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = guardedPlan(
                null,
                "codex-worker",
                TaskCreateTargetResolver.ExecutionRoute.DIRECT,
                null);
        TaskCreateCommandCoordinator.ProviderEffectGate gate =
                mock(TaskCreateCommandCoordinator.ProviderEffectGate.class);
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        doAnswer(invocation -> {
            Supplier<TaskCreateCommandCoordinator.ProviderEffectIdentity> identitySupplier =
                    invocation.getArgument(1);
            Runnable routePreparation = invocation.getArgument(2);
            Supplier<TaskCreateCommandCoordinator.PreparedProviderEffect<Object>>
                    preparedEffectSupplier = invocation.getArgument(3);
            identitySupplier.get();
            routePreparation.run();
            identitySupplier.get();

            nestedMetadata.put("value", "metadata-from-participant");
            nestedContext.put("value", "context-from-participant");
            attachment.put("value", "attachment-from-participant");
            request.setModel("model-from-participant");
            TaskCreateCommandCoordinator.PreparedProviderEffect<Object> prepared =
                    preparedEffectSupplier.get();

            nestedMetadata.put("value", "metadata-after-capture");
            nestedContext.put("value", "context-after-capture");
            attachment.put("value", "attachment-after-capture");
            request.setModel("model-after-capture");
            return prepared.execute();
        }).when(gate).invokePrepared(eq(plan), any(), any(), any());
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), isNull()))
                .thenAnswer(invocation -> {
                    Map<String, Object> params = invocation.getArgument(0);
                    assertEquals("metadata-from-participant",
                            assertInstanceOf(Map.class, params.get("nested")).get("value"));
                    Map<String, Object> providerContext = assertInstanceOf(
                            Map.class, params.get("context"));
                    assertEquals("context-from-participant",
                            assertInstanceOf(Map.class, providerContext.get("nested"))
                                    .get("value"));
                    List<Map<String, Object>> providerAttachments = assertInstanceOf(
                            List.class, params.get("attachments"));
                    assertEquals(
                            "attachment-from-participant",
                            providerAttachments.get(0).get("value"));
                    assertEquals("model-from-participant", params.get("model"));
                    assertTrue(InternalTaskDispatchMarkers
                            .requestsRuntimeAffinityInitialization(params));

                    ((Map<String, Object>) params.get("nested"))
                            .put("value", "metadata-from-provider");
                    ((Map<String, Object>) providerContext.get("nested"))
                            .put("value", "context-from-provider");
                    providerAttachments.get(0)
                            .put("value", "attachment-from-provider");
                    params.put("model", "model-from-provider");
                    return DispatchTaskDTO.builder()
                            .taskId("task-direct-snapshot-1")
                            .providerType("codex-worker")
                            .workerId("worker-1")
                            .build();
                });
        SessionTaskEntity storedTask = new SessionTaskEntity();
        storedTask.setTaskId("task-direct-snapshot-1");
        storedTask.setProviderType("codex-worker");
        when(sessionTaskRepository.findByTaskIdForUpdate("task-direct-snapshot-1"))
                .thenReturn(Optional.of(storedTask));

        DispatchTaskDTO result = facade.createTask(request, context, plan, gate);

        assertEquals("task-direct-snapshot-1", result.getTaskId());
        assertEquals("model-from-participant", storedTask.getModel());
        assertEquals("model-after-capture", request.getModel());
        assertEquals("metadata-after-capture", nestedMetadata.get("value"));
        assertEquals("context-after-capture", nestedContext.get("value"));
        assertEquals("attachment-after-capture", attachment.get("value"));
        assertFalse(InternalTaskDispatchMarkers.requestsRuntimeAffinityInitialization(
                request.getMetadata()));
        verify(sessionTaskRepository).save(storedTask);
    }

    @Test
    void guardedDirectRebuildsActualProviderIdentityAfterRoutePreparation() {
        LifecycleIngressGate lifecycleGate = mock(LifecycleIngressGate.class);
        LifecycleIngressGate.IngressPermit ingressPermit =
                mock(LifecycleIngressGate.IngressPermit.class);
        ReflectionTestUtils.setField(facade, "lifecycleIngressGate", lifecycleGate);
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-worker")
                .workerId("worker-1")
                .sessionId("session-direct-provider-drift")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-direct-provider-drift")
                .requestSource("UI")
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = guardedPlan(
                null,
                "codex-worker",
                TaskCreateTargetResolver.ExecutionRoute.DIRECT,
                "session-direct-provider-drift");
        AtomicReference<String> actualProviderType =
                new AtomicReference<>("codex-worker");
        when(taskQueryProvider.getProviderType())
                .thenAnswer(ignored -> actualProviderType.get());
        when(lifecycleGate.reserveBeforeEffect(
                "session-direct-provider-drift", "worker-1"))
                .thenReturn(ingressPermit);
        TaskCreateCommandCoordinator.ProviderEffectGate gate =
                mock(TaskCreateCommandCoordinator.ProviderEffectGate.class);
        doAnswer(invocation -> {
            Supplier<TaskCreateCommandCoordinator.ProviderEffectIdentity> identitySupplier =
                    invocation.getArgument(1);
            Runnable routePreparation = invocation.getArgument(2);
            TaskCreateCommandCoordinator.ProviderEffectIdentity beforeRoute =
                    identitySupplier.get();
            routePreparation.run();
            actualProviderType.set("codex-app-server-worker");
            TaskCreateCommandCoordinator.ProviderEffectIdentity afterRoute =
                    identitySupplier.get();
            assertEquals("codex-worker", beforeRoute.providerType());
            assertEquals("codex-app-server-worker", afterRoute.providerType());
            throw new IllegalStateException("TASK_CREATE_EFFECT_IDENTITY_CONFLICT");
        }).when(gate).invokePrepared(eq(plan), any(), any(), any());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> facade.createTask(request, context, plan, gate));

        assertEquals("TASK_CREATE_EFFECT_IDENTITY_CONFLICT", failure.getMessage());
        verify(lifecycleGate).reserveBeforeEffect(
                "session-direct-provider-drift", "worker-1");
        verify(taskQueryProvider, never()).createTaskDirect(any(), any(), any());
        verify(lifecycleGate, never()).confirm(any(), any());
        verify(lifecycleGate, never()).releaseFailed(any());
    }

    @Test
    void guardedDirectRechecksActualProviderIdentityAtCapturedEffect() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-worker")
                .workerId("worker-1")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = guardedPlan(
                null,
                "codex-worker",
                TaskCreateTargetResolver.ExecutionRoute.DIRECT,
                null);
        AtomicReference<String> actualProviderType =
                new AtomicReference<>("codex-worker");
        when(taskQueryProvider.getProviderType())
                .thenAnswer(ignored -> actualProviderType.get());
        TaskCreateCommandCoordinator.ProviderEffectGate gate =
                mock(TaskCreateCommandCoordinator.ProviderEffectGate.class);
        doAnswer(invocation -> {
            Supplier<TaskCreateCommandCoordinator.ProviderEffectIdentity> identitySupplier =
                    invocation.getArgument(1);
            Runnable routePreparation = invocation.getArgument(2);
            Supplier<TaskCreateCommandCoordinator.PreparedProviderEffect<Object>>
                    preparedEffectSupplier = invocation.getArgument(3);
            identitySupplier.get();
            routePreparation.run();
            identitySupplier.get();
            TaskCreateCommandCoordinator.PreparedProviderEffect<Object> prepared =
                    preparedEffectSupplier.get();
            actualProviderType.set("codex-app-server-worker");
            return prepared.execute();
        }).when(gate).invokePrepared(eq(plan), any(), any(), any());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> facade.createTask(request, context, plan, gate));

        assertEquals(
                "TASK_CREATE_DIRECT_PROVIDER_IDENTITY_CHANGED",
                failure.getMessage());
        verify(taskQueryProvider, never()).createTaskDirect(any(), any(), any());
    }

    @Test
    void guardedGateDenialSkipsDirectAndA2aRoutePreparation() {
        LifecycleIngressGate lifecycleGate = mock(LifecycleIngressGate.class);
        ReflectionTestUtils.setField(facade, "lifecycleIngressGate", lifecycleGate);
        @SuppressWarnings("unchecked")
        Map<String, Object> directMetadata = mock(Map.class);
        TaskDispatchRequest directRequest = TaskDispatchRequest.builder()
                .providerType("codex-worker")
                .workerId("worker-1")
                .sessionId("session-direct-denied")
                .metadata(directMetadata)
                .build();
        AgentResolveContext directContext = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-direct-denied")
                .requestSource("UI")
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan directPlan = guardedPlan(
                null, "codex-worker", TaskCreateTargetResolver.ExecutionRoute.DIRECT,
                "session-direct-denied");
        TaskCreateCommandCoordinator.ProviderEffectGate directGate =
                mock(TaskCreateCommandCoordinator.ProviderEffectGate.class);
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        doThrow(new IllegalStateException("TASK_CREATE_EFFECT_IDENTITY_CONFLICT"))
                .when(directGate).invokePrepared(
                        eq(directPlan), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> facade.createTask(
                directRequest, directContext, directPlan, directGate));

        verify(taskQueryProvider, never()).createTaskDirect(any(), any(), any());
        verifyNoInteractions(lifecycleGate);
        verifyNoInteractions(directMetadata);
        verify(directGate).invokePrepared(eq(directPlan), any(), any(), any());
        verify(directGate, never()).invoke(any(), any(), any());

        reset(lifecycleGate, agentResolver, agent);
        TaskDispatchRequest a2aRequest = TaskDispatchRequest.builder()
                .agentId("agent-a2a-denied")
                .providerType("claude-worker")
                .workerId("worker-1")
                .sessionId("session-a2a-denied")
                .build();
        AgentResolveContext a2aContext = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-a2a-denied")
                .requestSource("UI")
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan a2aPlan = guardedPlan(
                "agent-a2a-denied", "claude-worker",
                TaskCreateTargetResolver.ExecutionRoute.A2A,
                "session-a2a-denied");
        TaskCreateContextNormalizer normalizer = mock(TaskCreateContextNormalizer.class);
        TaskCreateContextNormalizer.PendingContextClaim pendingClaim =
                mock(TaskCreateContextNormalizer.PendingContextClaim.class);
        ReflectionTestUtils.setField(facade, "taskCreateContextNormalizer", normalizer);
        lenient().when(a2aPlan.pendingContextClaim()).thenReturn(pendingClaim);
        TaskCreateCommandCoordinator.ProviderEffectGate a2aGate =
                mock(TaskCreateCommandCoordinator.ProviderEffectGate.class);
        when(agentResolver.resolveAgentByProviderTypeExact(
                eq("claude-worker"), eq("agent-a2a-denied"), any()))
                .thenReturn(Optional.of(agent));
        when(agent.getAgentCard()).thenReturn(
                A2aAgentCard.builder().id("agent-a2a-denied").build());
        doThrow(new IllegalStateException("TASK_CREATE_EFFECT_IDENTITY_CONFLICT"))
                .when(a2aGate).invokePrepared(eq(a2aPlan), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> facade.createTask(
                a2aRequest, a2aContext, a2aPlan, a2aGate));

        verify(agent, never()).sendTask(any());
        verifyNoInteractions(lifecycleGate);
        verifyNoInteractions(bindingService);
        verify(normalizer, never()).claimPendingAfterPermit(any(), any());
    }

    @Test
    void guardedA2aRoutePreparationFailureNeverReleasesOrDispatches() {
        LifecycleIngressGate lifecycleGate = mock(LifecycleIngressGate.class);
        TaskCreateContextNormalizer normalizer = mock(TaskCreateContextNormalizer.class);
        TaskCreateContextNormalizer.PendingContextClaim pendingClaim =
                mock(TaskCreateContextNormalizer.PendingContextClaim.class);
        TaskCreateContextNormalizer.CanonicalContextProof contextProof =
                mock(TaskCreateContextNormalizer.CanonicalContextProof.class);
        ReflectionTestUtils.setField(facade, "lifecycleIngressGate", lifecycleGate);
        ReflectionTestUtils.setField(facade, "taskCreateContextNormalizer", normalizer);
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("agent-a2a-route-failure")
                .providerType("claude-worker")
                .workerId("worker-1")
                .sessionId("session-a2a-route-failure")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-a2a-route-failure")
                .requestSource("UI")
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan plan = guardedPlan(
                "agent-a2a-route-failure",
                "claude-worker",
                TaskCreateTargetResolver.ExecutionRoute.A2A,
                "session-a2a-route-failure");
        when(plan.pendingContextClaim()).thenReturn(pendingClaim);
        when(normalizer.claimPendingAfterPermit(pendingClaim, plan))
                .thenReturn(contextProof);
        when(agentResolver.resolveAgentByProviderTypeExact(
                eq("claude-worker"), eq("agent-a2a-route-failure"), any()))
                .thenReturn(Optional.of(agent));
        when(agent.getAgentCard()).thenReturn(
                A2aAgentCard.builder().id("agent-a2a-route-failure").build());
        doThrow(new IllegalStateException("binding unavailable"))
                .when(bindingService).getOrBind(
                        "session-a2a-route-failure",
                        "agent-a2a-route-failure",
                        "claude-worker",
                        "AGENT_ID");

        assertThrows(IllegalStateException.class, () -> facade.createTask(
                request, context, plan, passThroughGate()));

        InOrder ordered = inOrder(normalizer, bindingService);
        ordered.verify(normalizer).claimPendingAfterPermit(pendingClaim, plan);
        ordered.verify(bindingService).getOrBind(
                "session-a2a-route-failure",
                "agent-a2a-route-failure",
                "claude-worker",
                "AGENT_ID");
        verifyNoInteractions(lifecycleGate);
        verify(agent, never()).sendTask(any());
    }

    @Test
    void guardedPermittedProviderFailureRetainsReservationForBothRoutes() {
        LifecycleIngressGate lifecycleGate = mock(LifecycleIngressGate.class);
        LifecycleIngressGate.IngressPermit directPermit =
                mock(LifecycleIngressGate.IngressPermit.class);
        ReflectionTestUtils.setField(facade, "lifecycleIngressGate", lifecycleGate);
        TaskDispatchRequest directRequest = TaskDispatchRequest.builder()
                .providerType("codex-worker")
                .workerId("worker-1")
                .sessionId("session-direct-uncertain")
                .build();
        AgentResolveContext directContext = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-direct-uncertain")
                .requestSource("UI")
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan directPlan = guardedPlan(
                null, "codex-worker", TaskCreateTargetResolver.ExecutionRoute.DIRECT,
                "session-direct-uncertain");
        TaskCreateCommandCoordinator.ProviderEffectGate directGate = passThroughGate();
        when(lifecycleGate.reserveBeforeEffect("session-direct-uncertain", "worker-1"))
                .thenReturn(directPermit);
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), isNull()))
                .thenThrow(new IllegalStateException("provider outcome unknown"));

        assertThrows(IllegalStateException.class, () -> facade.createTask(
                directRequest, directContext, directPlan, directGate));

        verify(taskQueryProvider).createTaskDirect(any(), eq("user-1"), isNull());
        verify(lifecycleGate, never()).releaseFailed(directPermit);
        verify(lifecycleGate, never()).confirm(eq(directPermit), any());

        reset(lifecycleGate, agentResolver, agent);
        LifecycleIngressGate.IngressPermit a2aPermit =
                mock(LifecycleIngressGate.IngressPermit.class);
        TaskDispatchRequest a2aRequest = TaskDispatchRequest.builder()
                .agentId("agent-a2a-uncertain")
                .providerType("claude-worker")
                .workerId("worker-1")
                .sessionId("session-a2a-uncertain")
                .build();
        AgentResolveContext a2aContext = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-a2a-uncertain")
                .requestSource("UI")
                .build();
        TaskCreateTargetResolver.CreateExecutionPlan a2aPlan = guardedPlan(
                "agent-a2a-uncertain", "claude-worker",
                TaskCreateTargetResolver.ExecutionRoute.A2A,
                "session-a2a-uncertain");
        TaskCreateCommandCoordinator.ProviderEffectGate a2aGate = passThroughGate();
        when(lifecycleGate.reserveBeforeEffect("session-a2a-uncertain", "worker-1"))
                .thenReturn(a2aPermit);
        when(agentResolver.resolveAgentByProviderTypeExact(
                eq("claude-worker"), eq("agent-a2a-uncertain"), any()))
                .thenReturn(Optional.of(agent));
        when(agent.getAgentCard()).thenReturn(
                A2aAgentCard.builder().id("agent-a2a-uncertain").build());
        when(agent.sendTask(any()))
                .thenThrow(new IllegalStateException("provider outcome unknown"));

        assertThrows(IllegalStateException.class, () -> facade.createTask(
                a2aRequest, a2aContext, a2aPlan, a2aGate));

        verify(agent).sendTask(any());
        verify(lifecycleGate, never()).releaseFailed(a2aPermit);
        verify(lifecycleGate, never()).confirm(eq(a2aPermit), any());
    }

    @Test
    void resolveCreateExecutionPlanRejectsResumeContinuationBeforeTargetOrEffect() {
        ReflectionTestUtils.setField(
                facade, "agentConversationContextRepository", agentConversationContextRepository);
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("ctx-guarded-resume-1")
                .resume(true)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();
        AgentConversationContextEntity boundContext = new AgentConversationContextEntity();
        boundContext.setContextId("ctx-guarded-resume-1");
        boundContext.setUserId("user-1");
        boundContext.setTargetAgentId("agent-resume-1");
        boundContext.setNavigatorSessionId("session-guarded-resume-1");
        SessionEntity boundSession = new SessionEntity();
        boundSession.setId("session-guarded-resume-1");
        boundSession.setUserId("user-1");
        boundSession.setTenantId("tenant-1");
        boundSession.setAgentId("agent-resume-1");
        boundSession.setProviderType("claude-worker");
        boundSession.setCurrentWorkerId("worker-1");
        boundSession.setCurrentDirectoryId("dir-1");
        when(agentConversationContextRepository.findById("ctx-guarded-resume-1"))
                .thenReturn(Optional.of(boundContext));
        when(agentConversationContextRepository.findByContextIdAndUserId(
                "ctx-guarded-resume-1", "user-1"))
                .thenReturn(Optional.of(boundContext));
        when(resourceAccessService.requireOwnedSession(
                "session-guarded-resume-1", "user-1", "tenant-1"))
                .thenReturn(boundSession);
        when(sessionRepository.findById("session-guarded-resume-1"))
                .thenReturn(Optional.of(boundSession));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.resolveCreateExecutionPlan(request, context));

        assertTrue(error.getMessage().contains("resume continuation"));
        assertEquals("session-guarded-resume-1", request.getSessionId());
        assertEquals("agent-resume-1", request.getAgentId());
        assertEquals("claude-worker", request.getProviderType());
        assertEquals("worker-1", request.getWorkerId());
        assertEquals("dir-1", request.getDirectoryId());
        verifyNoInteractions(sessionCodingAgentRepository, workerManagementFacade, agentResolver, agent);
        verify(taskQueryProvider, never()).createTaskDirect(any(), any(), any());
    }

    @Test
    void normalizeAliasWinnerOverridesTemporaryContextWithoutMutatingExistingRows() {
        EntityManager entityManager = mock(EntityManager.class);
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);
        CodingAgentEntity codingAgent = ownedAgent(
                "agent-alias-1", "user-1", "tenant-1",
                "LOCAL_CLAUDE_WORKER", "worker-1");
        SessionEntity session = canonicalSession(
                "session-alias-1", "user-1", "tenant-1", "agent-alias-1",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER, "worker-1");
        AgentConversationContextEntity winner = new AgentConversationContextEntity();
        winner.setContextId("ctx-alias-winner");
        winner.setContextAlias("Order-A");
        winner.setUserId("user-1");
        winner.setTargetAgentId("agent-alias-1");
        winner.setAgentType(ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER);
        winner.setNavigatorSessionId("session-alias-1");
        when(agentConversationContextRepository.findById("temporary-ui-context"))
                .thenReturn(Optional.empty());
        when(agentConversationContextRepository.findByContextAliasAndUserIdAndTargetAgentId(
                "Order-A", "user-1", "agent-alias-1"))
                .thenReturn(Optional.of(winner));
        when(sessionCodingAgentRepository.findByAgentIdAndUserId("agent-alias-1", "user-1"))
                .thenReturn(Optional.of(codingAgent));
        when(resourceAccessService.requireOwnedSession(
                "session-alias-1", "user-1", "tenant-1"))
                .thenReturn(session);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("temporary-ui-context")
                .contextAlias("Order-A")
                .agentId("agent-alias-1")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();
        TaskCreateContextNormalizer.Inspection inspection =
                normalizer.inspect(request, context, false);
        assertNotNull(inspection);
        inspection.applyForResolution(request, context);
        TaskCreateContextNormalizer.CanonicalContextProof proof =
                normalizer.sealForResolution(inspection, normalizerPlan(
                        "user-1", "tenant-1", "agent-alias-1",
                        ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                        "worker-1", "session-alias-1"));

        assertEquals("ctx-alias-winner", request.getContextId());
        assertNull(request.getContextAlias());
        assertEquals("session-alias-1", request.getSessionId());
        assertEquals("Order-A", proof.contextAlias());
        assertFalse(proof.currentRequestCreated());
        verify(entityManager, never()).persist(any());
        verify(sessionRepository, never()).save(any());
        verify(agentConversationContextRepository, never()).save(any());
    }

    @Test
    void resolvePendingContextDefersMutationAndKeepsPlanBindingAcrossClaim() {
        EntityManager entityManager = mock(EntityManager.class);
        List<Object> persisted = new ArrayList<>();
        doAnswer(invocation -> {
            persisted.add(invocation.getArgument(0));
            return null;
        }).when(entityManager).persist(any());
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);
        ReflectionTestUtils.setField(facade, "taskCreateContextNormalizer", normalizer);
        when(agentConversationContextRepository.findByContextAliasAndUserIdAndTargetAgentId(
                "Deferred-A", "user-1", "agent-deferred-1"))
                .thenReturn(Optional.empty());
        when(sessionCodingAgentRepository.findByAgentIdAndUserId(
                "agent-deferred-1", "user-1"))
                .thenReturn(Optional.of(ownedAgent(
                        "agent-deferred-1",
                        "user-1",
                        "tenant-1",
                        "LOCAL_CLAUDE_WORKER",
                        "worker-1")));
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextAlias("Deferred-A")
                .agentId("agent-deferred-1")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        TaskCreateTargetResolver.CreateExecutionPlan plan =
                facade.resolveCreateExecutionPlan(request, context);

        assertNotNull(plan.pendingContextClaim());
        assertNull(plan.canonicalContextProof());
        assertEquals(plan.pendingContextClaim().canonicalContextId(), request.getContextId());
        assertEquals(plan.pendingContextClaim().navigatorSessionId(), request.getSessionId());
        assertEquals(request.getSessionId(), context.getSessionId());
        plan.requireMatches(request, context);
        TaskCreateCommandCoordinator.PlanBinding before =
                TaskCreateCommandCoordinator.PlanBinding.from(plan);
        assertTrue(persisted.isEmpty());
        verifyNoInteractions(entityManager);

        TaskCreateContextNormalizer.CanonicalContextProof proof =
                normalizer.claimPendingAfterPermit(plan.pendingContextClaim(), plan);
        TaskCreateCommandCoordinator.PlanBinding after =
                TaskCreateCommandCoordinator.PlanBinding.from(plan);

        assertEquals(request.getContextId(), proof.contextId());
        assertEquals(request.getSessionId(), proof.navigatorSessionId());
        assertEquals(before.tenantReference(), after.tenantReference());
        assertEquals(before.target(), after.target());
        assertEquals(before.effect(), after.effect());
        assertEquals(2, persisted.size());
        verify(entityManager).flush();
    }

    @Test
    void deferredContextClaimRereadsAndRejectsExistingSessionIdentityDrift() {
        EntityManager entityManager = mock(EntityManager.class);
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);
        SessionEntity inspectedSession = canonicalSession(
                "session-deferred-existing",
                "user-1",
                "tenant-1",
                "agent-deferred-existing",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                "worker-1");
        SessionEntity driftedSession = canonicalSession(
                "session-deferred-existing",
                "user-1",
                "tenant-1",
                "agent-deferred-existing",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                "worker-2");
        when(resourceAccessService.requireOwnedSession(
                "session-deferred-existing", "user-1", "tenant-1"))
                .thenReturn(inspectedSession);
        when(entityManager.find(
                SessionEntity.class,
                "session-deferred-existing",
                LockModeType.PESSIMISTIC_READ))
                .thenReturn(driftedSession);
        when(agentConversationContextRepository.findByContextAliasAndUserIdAndTargetAgentId(
                "Deferred-Existing", "user-1", "agent-deferred-existing"))
                .thenReturn(Optional.empty());
        when(sessionCodingAgentRepository.findByAgentIdAndUserId(
                "agent-deferred-existing", "user-1"))
                .thenReturn(Optional.of(ownedAgent(
                        "agent-deferred-existing",
                        "user-1",
                        "tenant-1",
                        "LOCAL_CLAUDE_WORKER",
                        "worker-1")));
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-deferred-existing")
                .contextAlias("Deferred-Existing")
                .agentId("agent-deferred-existing")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();
        TaskCreateContextNormalizer.Inspection inspection =
                normalizer.inspect(request, context, false);
        inspection.applyForResolution(request, context);
        TaskCreateTargetResolver.CreateExecutionPlan plan = normalizerPlan(
                "user-1",
                "tenant-1",
                "agent-deferred-existing",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                "worker-1",
                "session-deferred-existing");
        TaskCreateContextNormalizer.PendingContextClaim claim =
                normalizer.deferPendingForResolution(inspection, plan);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> normalizer.claimPendingAfterPermit(claim, plan));

        assertEquals("CONTEXT_CHANGED_CONCURRENTLY_RETRY", failure.getMessage());
        verify(entityManager).clear();
        verify(entityManager).find(
                SessionEntity.class,
                "session-deferred-existing",
                LockModeType.PESSIMISTIC_READ);
        verify(entityManager, never()).persist(any());
        verify(entityManager, never()).flush();
    }

    @Test
    void pendingContextCannotUsePrePermitSealWriteBypass() {
        EntityManager entityManager = mock(EntityManager.class);
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);
        when(agentConversationContextRepository.findByContextAliasAndUserIdAndTargetAgentId(
                "Deferred-Permit", "user-1", "agent-deferred-permit"))
                .thenReturn(Optional.empty());
        when(sessionCodingAgentRepository.findByAgentIdAndUserId(
                "agent-deferred-permit", "user-1"))
                .thenReturn(Optional.of(ownedAgent(
                        "agent-deferred-permit",
                        "user-1",
                        "tenant-1",
                        "LOCAL_CLAUDE_WORKER",
                        "worker-1")));
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextAlias("Deferred-Permit")
                .agentId("agent-deferred-permit")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();
        TaskCreateContextNormalizer.Inspection inspection =
                normalizer.inspect(request, context, false);
        inspection.applyForResolution(request, context);
        TaskCreateTargetResolver.CreateExecutionPlan plan = normalizerPlan(
                "user-1",
                "tenant-1",
                "agent-deferred-permit",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                "worker-1",
                request.getSessionId());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> normalizer.sealForResolution(inspection, plan));

        assertTrue(failure.getMessage().contains("Provider-effect permit"));
        verifyNoInteractions(entityManager);
    }

    @Test
    void normalizeAliasMissDefersThenClaimsPristineAppServerSessionAndContext() {
        EntityManager entityManager = mock(EntityManager.class);
        List<Object> persisted = new ArrayList<>();
        doAnswer(invocation -> {
            persisted.add(invocation.getArgument(0));
            return null;
        }).when(entityManager).persist(any());
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);
        when(agentConversationContextRepository.findByContextAliasAndUserIdAndTargetAgentId(
                "Case-New", "user-1", "agent-app-1"))
                .thenReturn(Optional.empty());
        when(sessionCodingAgentRepository.findByAgentIdAndUserId("agent-app-1", "user-1"))
                .thenReturn(Optional.of(ownedAgent(
                        "agent-app-1", "user-1", "tenant-1",
                        "LOCAL_CODEX_WORKER", "worker-app-1")));
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextAlias("Case-New")
                .agentId("agent-app-1")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        TaskCreateContextNormalizer.Inspection inspection =
                normalizer.inspect(request, context, false);
        assertNotNull(inspection);
        verifyNoInteractions(entityManager);
        inspection.applyForResolution(request, context);
        TaskCreateTargetResolver.CreateExecutionPlan plan = normalizerPlan(
                        "user-1", "tenant-1", "agent-app-1",
                        ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER,
                        "worker-app-1", request.getSessionId());
        TaskCreateContextNormalizer.PendingContextClaim claim =
                normalizer.deferPendingForResolution(inspection, plan);

        assertTrue(persisted.isEmpty());
        verifyNoInteractions(entityManager);

        TaskCreateContextNormalizer.CanonicalContextProof proof =
                normalizer.claimPendingAfterPermit(claim, plan);

        assertEquals(2, persisted.size());
        SessionEntity createdSession = persisted.stream()
                .filter(SessionEntity.class::isInstance)
                .map(SessionEntity.class::cast)
                .findFirst().orElseThrow();
        AgentConversationContextEntity createdContext = persisted.stream()
                .filter(AgentConversationContextEntity.class::isInstance)
                .map(AgentConversationContextEntity.class::cast)
                .findFirst().orElseThrow();
        assertEquals(ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER,
                createdSession.getProviderType());
        assertNull(createdSession.getCurrentWorkerId());
        assertNull(createdSession.getLatestTaskId());
        assertNull(createdSession.getProviderStateJson());
        assertEquals(createdSession.getId(), createdContext.getNavigatorSessionId());
        assertEquals("Case-New", createdContext.getContextAlias());
        assertTrue(proof.currentRequestCreated());
        assertTrue(proof.runtimeAffinityInitializationEligible());
        verify(entityManager).flush();
        verify(sessionRepository, never()).save(any());
        verify(agentConversationContextRepository, never()).save(any());
    }

    @Test
    void deferredContextClaimRejectsConcurrentFreshWinnerWithoutRepairOrSecondEffect() {
        EntityManager entityManager = mock(EntityManager.class);
        doAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof AgentConversationContextEntity) {
                throw new DataIntegrityViolationException("alias winner committed");
            }
            return null;
        }).when(entityManager).persist(any());
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);
        AgentConversationContextEntity winner = new AgentConversationContextEntity();
        winner.setContextId("ctx-race-winner");
        winner.setContextAlias("Race-A");
        winner.setUserId("user-1");
        winner.setTargetAgentId("agent-race-1");
        winner.setAgentType(ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER);
        winner.setNavigatorSessionId("session-race-winner");
        SessionEntity winnerSession = canonicalSession(
                "session-race-winner", "user-1", "tenant-1", "agent-race-1",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER, "worker-1");
        when(agentConversationContextRepository.findByContextAliasAndUserIdAndTargetAgentId(
                "Race-A", "user-1", "agent-race-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(sessionCodingAgentRepository.findByAgentIdAndUserId("agent-race-1", "user-1"))
                .thenReturn(Optional.of(ownedAgent(
                        "agent-race-1", "user-1", "tenant-1",
                        "LOCAL_CLAUDE_WORKER", "worker-1")));
        when(resourceAccessService.requireOwnedSession(
                "session-race-winner", "user-1", "tenant-1"))
                .thenReturn(winnerSession);
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextAlias("Race-A")
                .agentId("agent-race-1")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();
        TaskCreateContextNormalizer.Inspection inspection =
                normalizer.inspect(request, context, false);
        inspection.applyForResolution(request, context);
        TaskCreateTargetResolver.CreateExecutionPlan plan = normalizerPlan(
                "user-1", "tenant-1", "agent-race-1",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                "worker-1", request.getSessionId());
        TaskCreateContextNormalizer.PendingContextClaim claim =
                normalizer.deferPendingForResolution(inspection, plan);

        verifyNoInteractions(entityManager);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> normalizer.claimPendingAfterPermit(claim, plan));

        assertEquals("CONTEXT_CHANGED_CONCURRENTLY_RETRY", failure.getMessage());
        verify(entityManager, times(2)).persist(any());
        verify(sessionRepository, never()).save(any());
        verify(agentConversationContextRepository, never()).save(any());
    }

    @Test
    void deferredContextClaimRejectsDifferentSettledWinnerIdentity() {
        EntityManager entityManager = mock(EntityManager.class);
        doAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof AgentConversationContextEntity) {
                throw new DataIntegrityViolationException("alias winner committed");
            }
            return null;
        }).when(entityManager).persist(any());
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);
        AgentConversationContextEntity winner = new AgentConversationContextEntity();
        winner.setContextId("ctx-settled-winner");
        winner.setContextAlias("Race-Settled");
        winner.setUserId("user-1");
        winner.setTargetAgentId("agent-race-settled");
        winner.setAgentType(ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER);
        winner.setNavigatorSessionId("session-settled-winner");
        winner.setAgentSessionRef("provider-session-existing");
        SessionEntity winnerSession = canonicalSession(
                "session-settled-winner",
                "user-1",
                "tenant-1",
                "agent-race-settled",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                "worker-1");
        winnerSession.setLatestTaskId("task-existing");
        when(agentConversationContextRepository.findByContextAliasAndUserIdAndTargetAgentId(
                "Race-Settled", "user-1", "agent-race-settled"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(sessionCodingAgentRepository.findByAgentIdAndUserId(
                "agent-race-settled", "user-1"))
                .thenReturn(Optional.of(ownedAgent(
                        "agent-race-settled",
                        "user-1",
                        "tenant-1",
                        "LOCAL_CLAUDE_WORKER",
                        "worker-1")));
        when(resourceAccessService.requireOwnedSession(
                "session-settled-winner", "user-1", "tenant-1"))
                .thenReturn(winnerSession);
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextAlias("Race-Settled")
                .agentId("agent-race-settled")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();
        TaskCreateContextNormalizer.Inspection inspection =
                normalizer.inspect(request, context, false);
        inspection.applyForResolution(request, context);
        TaskCreateTargetResolver.CreateExecutionPlan plan = normalizerPlan(
                "user-1",
                "tenant-1",
                "agent-race-settled",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                "worker-1",
                request.getSessionId());
        TaskCreateContextNormalizer.PendingContextClaim claim =
                normalizer.deferPendingForResolution(inspection, plan);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> normalizer.claimPendingAfterPermit(claim, plan));

        assertEquals("CONTEXT_CHANGED_CONCURRENTLY_RETRY", failure.getMessage());
        assertNotEquals(claim.canonicalContextId(), winner.getContextId());
        assertNotEquals(claim.navigatorSessionId(), winner.getNavigatorSessionId());
        verify(entityManager, times(2)).persist(any());
        verify(sessionRepository, never()).save(any());
        verify(agentConversationContextRepository, never()).save(any());
    }

    @Test
    void normalizeFailsClosedForIncompleteOrMalformedExistingSessionsWithoutMutation() {
        EntityManager entityManager = mock(EntityManager.class);
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);
        SessionEntity missingProvider = canonicalSession(
                "session-incomplete", "user-1", "tenant-1", "agent-incomplete",
                null, "worker-1");
        when(resourceAccessService.requireOwnedSession(
                "session-incomplete", "user-1", "tenant-1"))
                .thenReturn(missingProvider);
        when(sessionCodingAgentRepository.findByAgentIdAndUserId("agent-incomplete", "user-1"))
                .thenReturn(Optional.of(ownedAgent(
                        "agent-incomplete", "user-1", "tenant-1",
                        "LOCAL_CLAUDE_WORKER", "worker-1")));
        TaskDispatchRequest incompleteRequest = TaskDispatchRequest.builder()
                .sessionId("session-incomplete")
                .agentId("agent-incomplete")
                .build();
        AgentResolveContext incompleteContext = AgentResolveContext.builder()
                .userId("user-1").tenantId("tenant-1").requestSource("UI").build();
        TaskCreateContextNormalizer.Inspection incompleteInspection =
                normalizer.inspect(incompleteRequest, incompleteContext, false);
        incompleteInspection.applyForResolution(incompleteRequest, incompleteContext);
        assertThrows(IllegalArgumentException.class, () -> normalizer.sealForResolution(
                incompleteInspection,
                normalizerPlan("user-1", "tenant-1", "agent-incomplete",
                        ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                        "worker-1", "session-incomplete")));

        SessionEntity malformedState = canonicalSession(
                "session-malformed", "user-1", "tenant-1", "agent-malformed",
                ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER, null);
        malformedState.setProviderStateJson("{not-json");
        when(resourceAccessService.requireOwnedSession(
                "session-malformed", "user-1", "tenant-1"))
                .thenReturn(malformedState);
        when(sessionCodingAgentRepository.findByAgentIdAndUserId("agent-malformed", "user-1"))
                .thenReturn(Optional.of(ownedAgent(
                        "agent-malformed", "user-1", "tenant-1",
                        "LOCAL_CODEX_WORKER", "worker-app-1")));
        TaskDispatchRequest malformedRequest = TaskDispatchRequest.builder()
                .sessionId("session-malformed")
                .agentId("agent-malformed")
                .build();
        AgentResolveContext malformedContext = AgentResolveContext.builder()
                .userId("user-1").tenantId("tenant-1").requestSource("UI").build();
        TaskCreateContextNormalizer.Inspection malformedInspection =
                normalizer.inspect(malformedRequest, malformedContext, false);
        malformedInspection.applyForResolution(malformedRequest, malformedContext);
        assertThrows(IllegalArgumentException.class, () -> normalizer.sealForResolution(
                malformedInspection,
                normalizerPlan("user-1", "tenant-1", "agent-malformed",
                        ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER,
                        "worker-app-1", "session-malformed")));

        verifyNoInteractions(entityManager);
        verify(sessionRepository, never()).save(any());
        verify(agentConversationContextRepository, never()).save(any());
    }

    @Test
    void guardedDecoratorConsumesSealedProofWithZeroStoreDuplicateOrProofLeak() {
        EntityManager entityManager = mock(EntityManager.class);
        TaskCreateContextNormalizer.CanonicalContextProof proof = existingContextProof(
                entityManager,
                "ctx-proof-claude", "session-proof-claude", "agent-proof-claude",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                "worker-proof-1", "LOCAL_CLAUDE_WORKER");
        InnerA2aAgent inner = mock(InnerA2aAgent.class);
        AtomicReference<A2aContext> observed = new AtomicReference<>();
        when(inner.sendTask(any(A2aContext.class))).thenAnswer(invocation -> {
            A2aContext actual = invocation.getArgument(0);
            observed.set(actual);
            assertFalse(actual.getMessage().getMetadata()
                    .containsKey(TaskCreateContextNormalizer.INTERNAL_PROOF_METADATA_KEY));
            return A2aTask.builder()
                    .id("task-proof-claude")
                    .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                    .history(List.of(actual.getMessage()))
                    .build();
        });
        CodingAgentEntity agentEntity = ownedAgent(
                "agent-proof-claude", "user-proof", "tenant-proof",
                "LOCAL_CLAUDE_WORKER", "worker-proof-1");
        ContextResolvingA2aAgent decorator = new ContextResolvingA2aAgent(
                inner, agentContextStore, agentEntity,
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("trace", "safe");
        metadata.put("agentId", "agent-proof-claude");
        metadata.put("providerType", ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER);
        metadata.put("sessionId", "session-proof-claude");
        metadata.put("contextId", "ctx-proof-claude");
        metadata.put("workerId", "worker-proof-1");
        metadata.put(TaskCreateContextNormalizer.INTERNAL_PROOF_METADATA_KEY, proof);
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("run")))
                .contextId("ctx-proof-claude")
                .metadata(metadata)
                .build();

        A2aTask task = decorator.sendTask(message);

        assertEquals("ctx-proof-claude", task.getContextId());
        assertEquals("session-proof-claude", observed.get().getNavigatorSessionId());
        assertEquals("user-proof", observed.get().getUserId());
        assertFalse(task.getHistory().get(0).getMetadata()
                .containsKey(TaskCreateContextNormalizer.INTERNAL_PROOF_METADATA_KEY));
        verifyNoInteractions(agentContextStore);
        verify(inner, never()).findRecentDuplicate(any());
        verify(inner, never()).rememberDuplicate(any(), any());
    }

    @Test
    void guardedAppServerUsesTemporaryAffinityMarkerWithoutProofOrTokenLeak() {
        EntityManager entityManager = mock(EntityManager.class);
        TaskCreateContextNormalizer.CanonicalContextProof proof = existingContextProof(
                entityManager,
                "ctx-proof-app", "session-proof-app", "agent-proof-app",
                ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER,
                "worker-app-1", "LOCAL_CODEX_WORKER");
        assertTrue(proof.runtimeAffinityInitializationEligible());
        InnerA2aAgent inner = mock(InnerA2aAgent.class);
        AtomicReference<A2aContext> observed = new AtomicReference<>();
        when(inner.sendTask(any(A2aContext.class))).thenAnswer(invocation -> {
            A2aContext actual = invocation.getArgument(0);
            observed.set(actual);
            assertTrue(InternalTaskDispatchMarkers.requestsRuntimeAffinityInitialization(
                    actual.getMessage().getMetadata()));
            assertFalse(actual.getMessage().getMetadata()
                    .containsKey(TaskCreateContextNormalizer.INTERNAL_PROOF_METADATA_KEY));
            return A2aTask.builder()
                    .id("task-proof-app")
                    .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                    .history(List.of(actual.getMessage()))
                    .build();
        });
        CodingAgentEntity agentEntity = ownedAgent(
                "agent-proof-app", "user-proof", "tenant-proof",
                "LOCAL_CODEX_WORKER", "worker-app-1");
        ContextResolvingA2aAgent decorator = new ContextResolvingA2aAgent(
                inner, agentContextStore, agentEntity,
                ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentId", "agent-proof-app");
        metadata.put("providerType", ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER);
        metadata.put("sessionId", "session-proof-app");
        metadata.put("contextId", "ctx-proof-app");
        metadata.put("workerId", "worker-app-1");
        metadata.put(TaskCreateContextNormalizer.INTERNAL_PROOF_METADATA_KEY, proof);
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("run app server")))
                .contextId("ctx-proof-app")
                .metadata(metadata)
                .build();

        A2aTask task = decorator.sendTask(message);

        assertFalse(InternalTaskDispatchMarkers.requestsRuntimeAffinityInitialization(
                observed.get().getMessage().getMetadata()));
        assertFalse(task.getHistory().get(0).getMetadata()
                .containsKey(TaskCreateContextNormalizer.INTERNAL_PROOF_METADATA_KEY));
        assertFalse(InternalTaskDispatchMarkers.requestsRuntimeAffinityInitialization(
                task.getHistory().get(0).getMetadata()));
        verifyNoInteractions(agentContextStore);
        verify(inner, never()).findRecentDuplicate(any());
        verify(inner, never()).rememberDuplicate(any(), any());
    }

    @Test
    void appServerPristineProofRequiresBothTaskStoresEmptyBeforeEffect() {
        EntityManager entityManager = mock(EntityManager.class);
        String sessionId = "session-proof-app-task-fence";
        String contextId = "ctx-proof-app-task-fence";
        String agentId = "agent-proof-app-task-fence";
        String providerType = ProviderRouteRegistry.PROVIDER_CODEX_APP_SERVER_WORKER;
        String userId = "user-proof";
        String tenantId = "tenant-proof";
        CodingAgentEntity agentEntity = ownedAgent(
                agentId, userId, tenantId, "LOCAL_CODEX_WORKER", "worker-app-1");
        SessionEntity session = canonicalSession(
                sessionId, userId, tenantId, agentId, providerType, null);
        AgentConversationContextEntity stored = new AgentConversationContextEntity();
        stored.setContextId(contextId);
        stored.setUserId(userId);
        stored.setTargetAgentId(agentId);
        stored.setAgentType(providerType);
        stored.setNavigatorSessionId(sessionId);
        when(agentConversationContextRepository.findById(contextId))
                .thenReturn(Optional.of(stored));
        when(sessionCodingAgentRepository.findByAgentIdAndUserId(agentId, userId))
                .thenReturn(Optional.of(agentEntity));
        when(resourceAccessService.requireOwnedSession(sessionId, userId, tenantId))
                .thenReturn(session);
        when(taskQueryProvider.getProviderType()).thenReturn(providerType);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId(contextId)
                .agentId(agentId)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .requestSource("UI")
                .build();
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);
        TaskCreateContextNormalizer.Inspection inspection =
                normalizer.inspect(request, context, false);
        assertNotNull(inspection);
        inspection.applyForResolution(request, context);
        TaskCreateTargetResolver.CreateExecutionPlan plan = normalizerPlan(
                userId, tenantId, agentId, providerType, "worker-app-1", sessionId);

        when(taskQueryProvider.listTasksBySession(sessionId)).thenReturn(List.of(
                DispatchTaskDTO.builder().taskId("provider-task").build()));
        when(sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc(sessionId))
                .thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.sealForResolution(inspection, plan));

        when(taskQueryProvider.listTasksBySession(sessionId)).thenReturn(List.of());
        when(codexSdkTaskLookupProvider.listTasksBySession(sessionId)).thenReturn(List.of(
                DispatchTaskDTO.builder().taskId("sdk-provider-task").build()));
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.sealForResolution(inspection, plan));

        when(codexSdkTaskLookupProvider.listTasksBySession(sessionId)).thenReturn(List.of());
        when(codexBizTaskLookupProvider.listTasksBySession(sessionId)).thenReturn(List.of(
                DispatchTaskDTO.builder().taskId("biz-provider-task").build()));
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.sealForResolution(inspection, plan));

        when(codexBizTaskLookupProvider.listTasksBySession(sessionId)).thenReturn(List.of());
        when(sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc(sessionId))
                .thenReturn(List.of(new SessionTaskEntity()));
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.sealForResolution(inspection, plan));

        when(sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc(sessionId))
                .thenReturn(List.of());
        TaskCreateContextNormalizer.CanonicalContextProof proof =
                normalizer.sealForResolution(inspection, plan);
        assertTrue(proof.runtimeAffinityInitializationEligible());
        verifyNoInteractions(entityManager);
    }

    @Test
    void canonicalCompletionUsesExactTaskFencedCas() {
        EntityManager entityManager = mock(EntityManager.class);
        TaskCreateContextNormalizer.CanonicalContextProof proof = existingContextProof(
                entityManager,
                "ctx-proof-cas", "session-proof-cas", "agent-proof-cas",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                "worker-proof-cas", "LOCAL_CLAUDE_WORKER");
        Query query = mock(Query.class);
        when(entityManager.createQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1);
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);

        normalizer.completeAgentSessionRef(proof, "task-proof-cas", "provider-ref-cas");

        var jpql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpql.capture());
        assertTrue(jpql.getValue().contains("s.latestTaskId = :taskId"));
        assertTrue(jpql.getValue().contains("s.currentWorkerId = :workerId"));
        verify(query).setParameter("taskId", "task-proof-cas");
        verify(query).setParameter("desiredRef", "provider-ref-cas");
        verify(sessionRepository, never()).save(any());
        verify(agentConversationContextRepository, never()).save(any());
    }

    @Test
    void guardedFacadeSkipsCanonicalCompletionForSyntheticFailedTask() {
        EntityManager entityManager = mock(EntityManager.class);
        TaskCreateContextNormalizer.CanonicalContextProof proof = existingContextProof(
                entityManager,
                "ctx-proof-failed", "session-proof-failed", "agent-proof-failed",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                "worker-proof-failed", "LOCAL_CLAUDE_WORKER");
        TaskCreateContextNormalizer normalizer = mock(TaskCreateContextNormalizer.class);
        ReflectionTestUtils.setField(facade, "taskCreateContextNormalizer", normalizer);
        TaskCreateTargetResolver.CreateExecutionPlan plan = guardedPlan(
                "agent-proof-failed", ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                TaskCreateTargetResolver.ExecutionRoute.A2A, "session-proof-failed");
        when(plan.canonicalContextProof()).thenReturn(proof);
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("agent-proof-failed")
                .providerType(ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER)
                .workerId("worker-1")
                .sessionId("session-proof-failed")
                .contextId("ctx-proof-failed")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-proof-failed")
                .requestSource("UI")
                .build();
        when(agentResolver.resolveAgentByProviderTypeExact(
                eq(ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER),
                eq("agent-proof-failed"), any())).thenReturn(Optional.of(agent));
        when(agent.getAgentCard()).thenReturn(
                A2aAgentCard.builder().id("agent-proof-failed").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("error-preflight")
                .contextId("ctx-proof-failed")
                .status(A2aTaskStatus.builder().state(A2aTaskState.FAILED).build())
                .build());

        DispatchTaskDTO result = facade.createTask(request, context, plan, passThroughGate());

        assertEquals("error-preflight", result.getTaskId());
        verify(normalizer, never()).completeAgentSessionRef(any(), anyString(), any());
        verifyNoInteractions(agentContextStore);
    }

    @Test
    void normalizeRejectsOwnerAgentTenantAndSessionMismatchWithoutMutation() {
        EntityManager entityManager = mock(EntityManager.class);
        TaskCreateContextNormalizer normalizer = newContextNormalizer(entityManager);

        AgentConversationContextEntity foreignOwner = new AgentConversationContextEntity();
        foreignOwner.setContextId("ctx-foreign-owner");
        foreignOwner.setUserId("another-user");
        foreignOwner.setTargetAgentId("agent-scope");
        when(agentConversationContextRepository.findById("ctx-foreign-owner"))
                .thenReturn(Optional.of(foreignOwner));
        assertThrows(SecurityException.class, () -> normalizer.inspect(
                TaskDispatchRequest.builder()
                        .contextId("ctx-foreign-owner").agentId("agent-scope").build(),
                AgentResolveContext.builder()
                        .userId("user-1").tenantId("tenant-1").build(),
                false));

        AgentConversationContextEntity conflictingAgent = new AgentConversationContextEntity();
        conflictingAgent.setContextId("ctx-agent-conflict");
        conflictingAgent.setUserId("user-1");
        conflictingAgent.setTargetAgentId("agent-other");
        when(agentConversationContextRepository.findById("ctx-agent-conflict"))
                .thenReturn(Optional.of(conflictingAgent));
        assertThrows(IllegalArgumentException.class, () -> normalizer.inspect(
                TaskDispatchRequest.builder()
                        .contextId("ctx-agent-conflict").agentId("agent-scope").build(),
                AgentResolveContext.builder()
                        .userId("user-1").tenantId("tenant-1").build(),
                false));

        SessionEntity requestedSession = canonicalSession(
                "session-requested", "user-1", "tenant-1", "agent-scope",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER, "worker-1");
        AgentConversationContextEntity conflictingSession = new AgentConversationContextEntity();
        conflictingSession.setContextId("ctx-session-conflict");
        conflictingSession.setUserId("user-1");
        conflictingSession.setTargetAgentId("agent-scope");
        conflictingSession.setAgentType(ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER);
        conflictingSession.setNavigatorSessionId("session-other");
        when(agentConversationContextRepository.findById("ctx-session-conflict"))
                .thenReturn(Optional.of(conflictingSession));
        when(resourceAccessService.requireOwnedSession(
                "session-requested", "user-1", "tenant-1"))
                .thenReturn(requestedSession);
        when(sessionCodingAgentRepository.findByAgentIdAndUserId("agent-scope", "user-1"))
                .thenReturn(Optional.of(ownedAgent(
                        "agent-scope", "user-1", "tenant-1",
                        "LOCAL_CLAUDE_WORKER", "worker-1")));
        assertThrows(IllegalArgumentException.class, () -> normalizer.inspect(
                TaskDispatchRequest.builder()
                        .contextId("ctx-session-conflict")
                        .sessionId("session-requested")
                        .agentId("agent-scope")
                        .build(),
                AgentResolveContext.builder()
                        .userId("user-1").tenantId("tenant-1").build(),
                false));

        SessionEntity foreignTenant = canonicalSession(
                "session-foreign-tenant", "user-1", "tenant-other", "agent-scope",
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER, "worker-1");
        when(resourceAccessService.requireOwnedSession(
                "session-foreign-tenant", "user-1", "tenant-1"))
                .thenReturn(foreignTenant);
        assertThrows(SecurityException.class, () -> normalizer.inspect(
                TaskDispatchRequest.builder()
                        .sessionId("session-foreign-tenant")
                        .agentId("agent-scope")
                        .build(),
                AgentResolveContext.builder()
                        .userId("user-1").tenantId("tenant-1").build(),
                false));

        verifyNoInteractions(entityManager);
        verify(sessionRepository, never()).save(any());
        verify(agentConversationContextRepository, never()).save(any());
    }

    @Test
    void createTaskRejectsAnExistingSessionOwnedByAnotherUserBeforeRouting() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-private")
                .workerId("worker-1")
                .prompt("inject")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("attacker")
                .tenantId("tenant-1")
                .sessionId("session-private")
                .requestSource("UI")
                .build();
        doThrow(new SecurityException("Resource access denied"))
                .when(resourceAccessService)
                .requireOwnedSession("session-private", "attacker", "tenant-1");

        SecurityException error = assertThrows(
                SecurityException.class, () -> facade.createTask(request, context));

        assertEquals("Resource access denied", error.getMessage());
        verifyNoInteractions(agentResolver, bindingService, taskQueryProvider);
    }

    @Test
    void createTask_sdkSessionRejectsDirectAppServerRouteBeforeProviderInvocation() {
        TypedTaskProvider sdkProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider appServerProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(sdkProvider, appServerProvider));
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-sdk-1")
                .providerType("codex-app-server-worker")
                .prompt("switch")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .build();
        SessionEntity session = new SessionEntity();
        session.setId("session-sdk-1");
        session.setUserId("user-1");
        session.setProviderType("codex-worker");

        when(sessionRepository.findById("session-sdk-1")).thenReturn(Optional.of(session));
        when(sdkProvider.getProviderType()).thenReturn("codex-worker");
        when(appServerProvider.getProviderType()).thenReturn("codex-app-server-worker");

        assertThrows(SessionProviderBoundMismatchException.class,
                () -> facade.createTask(request, context));
        verify(sdkProvider, never()).createTaskDirect(any(), any(), any());
        verify(appServerProvider, never()).createTaskDirect(any(), any(), any());
        verifyNoInteractions(agentResolver, bindingService);
    }

    @Test
    void createTask_appServerSessionRejectsDirectSdkRouteBeforeProviderInvocation() {
        TypedTaskProvider sdkProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider appServerProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(sdkProvider, appServerProvider));
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-app-1")
                .providerType("codex-worker")
                .prompt("switch")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .build();
        SessionEntity session = new SessionEntity();
        session.setId("session-app-1");
        session.setUserId("user-1");
        session.setProviderType("codex-app-server-worker");

        when(sessionRepository.findById("session-app-1")).thenReturn(Optional.of(session));
        when(sdkProvider.getProviderType()).thenReturn("codex-worker");

        assertThrows(SessionProviderBoundMismatchException.class,
                () -> facade.createTask(request, context));
        verify(sdkProvider, never()).createTaskDirect(any(), any(), any());
        verify(appServerProvider, never()).createTaskDirect(any(), any(), any());
        verifyNoInteractions(agentResolver, bindingService);
    }

    @Test
    void createTask_imagesPassedAsStringNotListInA2aMessage() {
        // Bug: buildMessage() 曾直接将 List<String> 放入 metadata，
        // 导致 ClaudeWorkerA2aAgent 用 (String) 强转时 ClassCastException。
        // 修复后应转为 String（JSON）传递。
        String imageJson = "[{\"name\":\"screenshot.webp\",\"data\":\"base64...\",\"mime_type\":\"image/webp\"}]";
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("agent-1")
                .workerId("worker-1")
                .prompt("看图")
                .images(List.of(imageJson))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-1").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-img-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        facade.createTask(request, context);

        // 验证传给 agent.sendTask() 的 A2aMessage 中 images 是 String 类型
        var messageCaptor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(messageCaptor.capture());
        A2aMessage captured = messageCaptor.getValue();
        Object imagesValue = captured.getMetadata().get("images");
        assertNotNull(imagesValue, "images should be present in metadata");
        assertInstanceOf(String.class, imagesValue,
                "images in A2aMessage metadata must be String, not List, to avoid ClassCastException in downstream consumers");
        assertEquals(imageJson, imagesValue);
    }

    @Test
    void submitTask_routesThroughCreateTaskAndPreservesA2aMetadata() {
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(com.foggy.navigator.common.dto.a2a.A2aPart.text("run smoke")))
                .contextId("ctx-1")
                .metadata(Map.of(
                        "runtimeContext", Map.of("task_scoped_token", "token-1"),
                        "modelConfigId", "cfg-1"))
                .build();
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .resolveContext(AgentResolveContext.builder()
                        .userId("user-1")
                        .tenantId("tenant-1")
                        .modelConfigId("cfg-1")
                        .requestSource("OPEN_API")
                        .build())
                .message(message)
                .prompt("run smoke")
                .workerId("worker-1")
                .directoryId("dir-1")
                .modelConfigId("cfg-1")
                .model("codex-latest")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-1"), any())).thenReturn(Optional.of("codex-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-1").build());
        when(llmModelManager.getModelConfig("cfg-1")).thenReturn(Optional.of(modelConfig));
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .metadata(Map.of("sessionId", "session-1", "modelConfigId", "cfg-1"))
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        A2aTask result = facade.submitTask(request);

        assertEquals("task-1", result.getId());
        assertEquals("ctx-1", result.getContextId());
        assertEquals("session-1", result.getMetadata().get("sessionId"));
        assertEquals("cfg-1", result.getMetadata().get("modelConfigId"));
        var messageCaptor = org.mockito.ArgumentCaptor.forClass(A2aMessage.class);
        verify(agent).sendTask(messageCaptor.capture());
        assertEquals("token-1",
                ((Map<?, ?>) messageCaptor.getValue().getMetadata().get("runtimeContext")).get("task_scoped_token"));
        assertEquals("worker-1", messageCaptor.getValue().getMetadata().get("workerId"));
        assertEquals("dir-1", messageCaptor.getValue().getMetadata().get("directoryId"));
    }

    @Test
    void submitTask_persistsRecoveryCorrelationMetadataToTaskState() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        ReflectionTestUtils.setField(facade, "transactionManager", transactionManager);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(com.foggy.navigator.common.dto.a2a.A2aPart.text("recover task")))
                .contextId("ctx-1")
                .metadata(Map.of(
                        "originalTaskId", "task-original",
                        "recoveryCorrelationKey", "world-run/contract-1",
                        "attemptNumber", 2,
                        "idempotencyKey", "idem-1"))
                .build();
        AgentTaskSubmitRequest request = AgentTaskSubmitRequest.builder()
                .agentId("agent-1")
                .resolveContext(AgentResolveContext.builder()
                        .userId("user-1")
                        .tenantId("tenant-1")
                        .requestSource("OPEN_API")
                        .build())
                .message(message)
                .prompt("recover task")
                .contextId("ctx-1")
                .build();
        SessionTaskEntity entity = sessionTask(
                "task-recovery-1", "session-1", "claude-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 5, 27, 9, 0), "{}");

        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-1").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-recovery-1")
                .contextId("ctx-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());
        when(sessionTaskRepository.findByTaskIdForUpdate("task-recovery-1")).thenReturn(Optional.of(entity));

        facade.submitTask(request);

        InOrder updateOrder = inOrder(transactionManager, sessionTaskRepository);
        updateOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        updateOrder.verify(sessionTaskRepository).findByTaskIdForUpdate("task-recovery-1");
        updateOrder.verify(sessionTaskRepository).save(entity);
        updateOrder.verify(transactionManager).commit(transactionStatus);
        String taskStateJson = entity.getTaskStateJson();
        assertTrue(taskStateJson.contains("\"contextId\":\"ctx-1\""));
        assertTrue(taskStateJson.contains("\"originalTaskId\":\"task-original\""));
        assertTrue(taskStateJson.contains("\"recoveryCorrelationKey\":\"world-run/contract-1\""));
        assertTrue(taskStateJson.contains("\"attemptNumber\":2"));
        assertTrue(taskStateJson.contains("\"idempotencyKey\":\"idem-1\""));
        assertTrue(taskStateJson.contains("\"schemaVersion\":1"));
        assertTrue(taskStateJson.contains("\"providerType\":\"claude-worker\""));
    }

    @Test
    void createTask_usesDirectProviderRouteWhenModelConfigTargetsCodex() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("hi")
                .model("gpt-5.4")
                .modelConfigId("cfg-codex")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-1")
                .providerType("codex-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .build();

        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-1", result.getTaskId());
        verify(taskQueryProvider).createTaskDirect(any(), eq("user-1"), eq("tenant-1"));
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_usesExplicitCodexBizProviderWithOpenAICodexModelConfig() {
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("run actor task")
                .model("gpt-5.4")
                .modelConfigId("cfg-codex")
                .metadata(Map.of("codexHomeKey", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-1")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-biz-1", result.getTaskId());
        assertEquals("codex-biz-worker", result.getProviderType());
        verify(codexBizProvider).createTaskDirect(
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("codexHomeKey"))
                        && "cfg-codex".equals(params.get("modelConfigId"))
                        && "worker-1".equals(params.get("workerId"))),
                eq("user-1"),
                eq("tenant-1"));
        verify(codexProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_directCodexBizPersistsScopedHomeBindingInSessionState() {
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .prompt("run actor task")
                .modelConfigId("cfg-codex")
                .metadata(Map.of(
                        "codexHomeKey", "tenant/world-sim/scenario-1/home-1",
                        "privateAccountId", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-1")
                .contextId("ctx-codex-biz-new")
                .sessionId("session-codex-biz-new")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-biz-new");

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1"))).thenReturn(directTask);
        when(sessionRepository.findById("session-codex-biz-new")).thenReturn(Optional.of(session));

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-biz-1", result.getTaskId());
        verify(codexBizProvider).createTaskDirect(
                argThat(params -> "tenant/world-sim/scenario-1/home-1".equals(params.get("codexHomeKey"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("privateAccountId"))),
                eq("user-1"),
                eq("tenant-1"));
        verify(sessionRepository).save(argThat(saved -> {
            Map<String, Object> state = ProviderStateCodec.parseObject(saved.getProviderStateJson());
            return "session-codex-biz-new".equals(saved.getId())
                    && "tenant/world-sim/scenario-1/home-1".equals(
                    state.get(ProviderStateCodec.FIELD_CODEX_HOME_KEY))
                    && "tenant/world-sim/scenario-1/actor-1".equals(
                    state.get(ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID));
        }));
        verify(agentContextStore).saveSessionRefFull(
                "ctx-codex-biz-new",
                "codex-biz-worker",
                null,
                "session-codex-biz-new",
                "user-1",
                "codex-biz-worker",
                null);
        verify(codexProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_usesExplicitCodexBizProviderEvenWhenLogicalAgentIdIsPresent() {
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("world-sim-agent")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("run actor task")
                .model("gpt-5.4")
                .modelConfigId("cfg-codex")
                .metadata(Map.of("codexHomeKey", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("OPEN_API")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-agent-1")
                .agentId("world-sim-agent")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-biz-agent-1", result.getTaskId());
        assertEquals("world-sim-agent", result.getAgentId());
        assertEquals("codex-biz-worker", result.getProviderType());
        verify(codexBizProvider).createTaskDirect(
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "world-sim-agent".equals(params.get("agentId"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("codexHomeKey"))
                        && "cfg-codex".equals(params.get("modelConfigId"))
                        && "worker-1".equals(params.get("workerId"))),
                eq("user-1"),
                eq("tenant-1"));
        verify(codexProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_usesExplicitCodexBizProviderFromDirectoryDefaultCodexModelConfig() {
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));
        ReflectionTestUtils.setField(facade, "workingDirectoryRepository", workingDirectoryRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("directory#dir-codex-biz")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .prompt("run directory actor task")
                .metadata(Map.of("codexHomeKey", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        WorkingDirectoryEntity directory = directoryEntity("dir-codex-biz", "World Sim");
        directory.setDefaultModelConfigId("cfg-codex");

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-directory-1")
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-codex-biz")
                .modelConfigId("cfg-codex")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(workingDirectoryRepository.findByDirectoryId("dir-codex-biz")).thenReturn(Optional.of(directory));
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-biz-directory-1", result.getTaskId());
        assertEquals("codex-biz-worker", result.getProviderType());
        verify(codexBizProvider).createTaskDirect(
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("codexHomeKey"))
                        && "cfg-codex".equals(params.get("modelConfigId"))
                        && "dir-codex-biz".equals(params.get("directoryId"))),
                eq("user-1"),
                eq("tenant-1"));
        verify(codexProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_rejectsExplicitCodexBizProviderWithNonCodexModelConfig() {
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("run actor task")
                .modelConfigId("cfg-claude")
                .metadata(Map.of("codexHomeKey", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("CLAUDE_CODE");

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-claude")).thenReturn(Optional.of(modelConfig));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));

        assertTrue(error.getMessage().contains("cfg-claude"));
        assertTrue(error.getMessage().contains("claude-worker"));
        assertTrue(error.getMessage().contains("codex-biz-worker"));
        verify(codexBizProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verify(codexProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_usesDirectProviderRouteWhenModelConfigTargetsGemini() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-gemini-1")
                .directoryId("dir-gemini-1")
                .prompt("hi gemini")
                .model("gemini-flash")
                .modelConfigId("cfg-gemini")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("GEMINI_CLI");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-gemini-1")
                .providerType("gemini-worker")
                .workerId("worker-gemini-1")
                .directoryId("dir-gemini-1")
                .model("gemini-flash")
                .build();

        when(llmModelManager.getModelConfig("cfg-gemini")).thenReturn(Optional.of(modelConfig));
        when(taskQueryProvider.getProviderType()).thenReturn("gemini-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-gemini-1", result.getTaskId());
        assertEquals("gemini-worker", result.getProviderType());
        verify(taskQueryProvider).createTaskDirect(
                argThat(params -> "worker-gemini-1".equals(params.get("workerId"))
                        && "dir-gemini-1".equals(params.get("directoryId"))
                        && "gemini-flash".equals(params.get("model"))
                        && "cfg-gemini".equals(params.get("modelConfigId"))),
                eq("user-1"),
                eq("tenant-1"));
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_usesDirectProviderRouteWhenModelConfigTargetsLangGraphBiz() {
        List<Map<String, Object>> attachments = List.of(Map.of(
                "name", "evidence.txt",
                "mimeType", "text/plain"
        ));
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-langgraph-1")
                .directoryId("dir-langgraph-1")
                .prompt("hi langgraph")
                .model("biz-default")
                .modelConfigId("cfg-langgraph")
                .context(Map.of("language", "fsscript", "script", "return 1;"))
                .attachments(attachments)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("LANGGRAPH_BIZ");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-langgraph-1")
                .providerType("langgraph-biz-worker")
                .workerId("worker-langgraph-1")
                .directoryId("dir-langgraph-1")
                .model("biz-default")
                .build();

        when(llmModelManager.getModelConfig("cfg-langgraph")).thenReturn(Optional.of(modelConfig));
        when(taskQueryProvider.getProviderType()).thenReturn("langgraph-biz-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-langgraph-1", result.getTaskId());
        assertEquals("langgraph-biz-worker", result.getProviderType());
        verify(taskQueryProvider).createTaskDirect(
                argThat(params -> "worker-langgraph-1".equals(params.get("workerId"))
                        && "dir-langgraph-1".equals(params.get("directoryId"))
                        && "biz-default".equals(params.get("model"))
                        && "cfg-langgraph".equals(params.get("modelConfigId"))
                        && Map.of("language", "fsscript", "script", "return 1;").equals(params.get("context"))
                        && attachments.equals(params.get("attachments"))),
                eq("user-1"),
                eq("tenant-1"));
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void createTask_a2aRouteIncludesParentSessionIdFromCreatedSession() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-child-1")
                .agentId("agent-1")
                .workerId("worker-1")
                .prompt("forwarded task")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-child-1")
                .requestSource("UI")
                .build();

        SessionEntity childSession = new SessionEntity();
        childSession.setId("session-child-1");
        childSession.setUserId("user-1");
        childSession.setParentSessionId("session-parent-1");

        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-1").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-forward-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .metadata(Map.of(
                        "sessionId", "session-child-1",
                        "workerId", "worker-1"
                ))
                .build());
        when(sessionRepository.findById("session-child-1")).thenReturn(Optional.of(childSession));

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("session-child-1", result.getSessionId());
        assertEquals("session-parent-1", result.getParentSessionId());
    }

    @Test
    void createTask_a2aRoutePrefersMetadataForImmediateResponse() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .agentId("agent-1")
                .workerId("worker-1")
                .prompt("forwarded task")
                .model("legacy-model")
                .modelConfigId("cfg-legacy")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-1").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-forward-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .metadata(Map.of(
                        "sessionId", "session-1",
                        "workerId", "worker-1",
                        "workerTaskId", "worker-task-1",
                        "model", "glm4.7",
                        "modelConfigId", "cfg-new"
                ))
                .build());

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("glm4.7", result.getModel());
        assertEquals("cfg-new", result.getModelConfigId());
        assertEquals("worker-task-1", result.getWorkerTaskId());
    }

    @Test
    void createTask_usesModelConfigIdForDirectRoute() {
        LlmModelConfigDTO codexConfig = new LlmModelConfigDTO();
        codexConfig.setWorkerBackend("OPENAI_CODEX");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(codexConfig));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("hi")
                .modelConfigId("cfg-codex")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-provider-1")
                .providerType("codex-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .build();

        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-provider-1", result.getTaskId());
        verify(taskQueryProvider).createTaskDirect(
                argThat(params -> "worker-1".equals(params.get("workerId"))
                        && "dir-2".equals(params.get("directoryId"))),
                eq("user-1"),
                eq("tenant-1"));
    }

    @Test
    void createTask_rejectsModelConfigThatTargetsDifferentProviderThanResolvedAgent() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .agentId("agent-claude-1")
                .workerId("worker-1")
                .prompt("hi")
                .modelConfigId("cfg-codex")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        when(agentResolver.resolveAgent(eq("agent-claude-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-claude-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-claude-1").build());
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));

        assertTrue(error.getMessage().contains("cfg-codex"));
        assertTrue(error.getMessage().contains("codex-worker"));
        assertTrue(error.getMessage().contains("claude-worker"));
        verify(agent, never()).sendTask(any());
        verifyNoInteractions(bindingService);
    }

    @Test
    void createTask_rejectsExplicitProviderTypeThatConflictsWithResolvedAgent() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .agentId("agent-claude-1")
                .workerId("worker-1")
                .prompt("hi")
                .providerType("codex-worker")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        when(agentResolver.resolveAgent(eq("agent-claude-1"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("agent-claude-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-claude-1").build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));

        assertTrue(error.getMessage().contains("codex-worker"));
        assertTrue(error.getMessage().contains("claude-worker"));
        verify(agent, never()).sendTask(any());
        verifyNoInteractions(bindingService, llmModelManager);
    }

    @Test
    void listTasksPaged_aggregatesSessionsAcrossProviders() {
        TypedTaskProvider claudeProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(claudeProvider, codexProvider));

        DispatchTaskDTO claudeTask = DispatchTaskDTO.builder()
                .taskId("task-claude-1")
                .sessionId("session-claude-1")
                .workerId("worker-1")
                .createdAt(LocalDateTime.of(2026, 3, 24, 21, 0))
                .build();
        DispatchTaskDTO codexTask = DispatchTaskDTO.builder()
                .taskId("task-codex-1")
                .sessionId("session-codex-1")
                .workerId("worker-1")
                .createdAt(LocalDateTime.of(2026, 3, 24, 22, 0))
                .build();

        when(claudeProvider.listTaskPage("user-1", 0, 20, null))
                .thenReturn(TaskPageResult.of(List.of(claudeTask), 1L, 0, 20));
        when(codexProvider.listTaskPage("user-1", 0, 20, null))
                .thenReturn(TaskPageResult.of(List.of(codexTask), 1L, 0, 20));

        Object result = facade.listTasksPaged("user-1", 0, 20, null);

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        assertEquals(2L, page.get("totalSessions"));
        List<?> content = assertInstanceOf(List.class, page.get("content"));
        assertEquals(2, content.size());
        assertEquals("task-codex-1", ((DispatchTaskDTO) content.get(0)).getTaskId());
        assertEquals("task-claude-1", ((DispatchTaskDTO) content.get(1)).getTaskId());
    }

    @Test
    void resumeTask_routesCodexThreadResumeToProvider() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-1")
                .prompt("continue")
                .providerType("codex-worker")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-2")
                .sessionId("session-1")
                .codexThreadId("thread-1")
                .providerType("codex-worker")
                .build();

        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-codex-2", result.getTaskId());
        assertEquals("thread-1", result.getCodexThreadId());
        verify(taskQueryProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "session-1".equals(params.get("sessionId"))
                        && "continue".equals(params.get("prompt"))));
        verifyNoInteractions(agentResolver);
    }

    @Test
    void resumeTask_routesGeminiSessionResumeToProvider() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-gemini-1")
                .sessionId("session-gemini-1")
                .prompt("continue gemini")
                .providerType("gemini-worker")
                .model("gemini-flash")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-gemini-1")
                .requestSource("UI")
                .build();

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-gemini-2")
                .sessionId("session-gemini-1")
                .geminiSessionId("gemini-session-1")
                .providerType("gemini-worker")
                .build();

        when(taskQueryProvider.getProviderType()).thenReturn("gemini-worker");
        when(taskQueryProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-gemini-2", result.getTaskId());
        assertEquals("gemini-session-1", result.getGeminiSessionId());
        verify(taskQueryProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "session-gemini-1".equals(params.get("sessionId"))
                        && "continue gemini".equals(params.get("prompt"))
                        && "worker-gemini-1".equals(params.get("workerId"))
                        && "gemini-flash".equals(params.get("model"))));
        verifyNoInteractions(agentResolver);
    }

    @Test
    void resumeTask_prefersSessionBoundProviderTypeOverLookupFallback() {
        TypedTaskProvider claudeProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(claudeProvider, codexProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .directoryId("dir-1")
                .sessionId("session-codex-1")
                .prompt("continue")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-codex-1")
                .requestSource("UI")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-1");
        session.setUserId("user-1");
        session.setProviderType("codex-worker");
        when(sessionRepository.findById("session-codex-1")).thenReturn(Optional.of(session));
        // 显式模拟历史 bug 场景：如果错误回退到 lookup，会把同目录/worker 解析成 claude-worker。
        lenient().when(agentResolver.getProviderType(eq("dir-1"), any())).thenReturn(Optional.of("claude-worker"));
        lenient().when(agentResolver.getProviderType(eq("worker-1"), any())).thenReturn(Optional.of("claude-worker"));

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-resumed")
                .sessionId("session-codex-1")
                .providerType("codex-worker")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(claudeProvider.getProviderType()).thenReturn("claude-worker");
        when(codexProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-codex-resumed", result.getTaskId());
        verify(codexProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "session-codex-1".equals(params.get("sessionId"))
                        && "continue".equals(params.get("prompt"))));
        verifyNoInteractions(agentResolver);
        verify(claudeProvider, never()).resumeTask(anyString(), anyString(), any());
    }

    @Test
    void resumeTask_rejectsModelConfigThatConflictsWithSessionBoundProvider() {
        TypedTaskProvider geminiProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(geminiProvider, codexProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-gemini-1")
                .sessionId("session-gemini-legacy")
                .prompt("continue gemini")
                .modelConfigId("cfg-codex")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-gemini-legacy")
                .requestSource("UI")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-gemini-legacy");
        session.setUserId("user-1");
        session.setProviderType("gemini-worker");
        when(sessionRepository.findById("session-gemini-legacy")).thenReturn(Optional.of(session));

        LlmModelConfigDTO codexConfig = new LlmModelConfigDTO();
        codexConfig.setWorkerBackend("OPENAI_CODEX");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(codexConfig));

        SessionProviderBoundMismatchException error = assertThrows(
                SessionProviderBoundMismatchException.class,
                () -> facade.resumeTask(request, context));

        assertEquals("gemini-worker", error.getBoundProviderType());
        assertEquals("codex-worker", error.getRequestedProviderType());
        verify(geminiProvider, never()).resumeTask(anyString(), anyString(), any());
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void resumeTask_sdkSessionRejectsAppServerModelConfig() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-codex-1")
                .prompt("continue")
                .modelConfigId("cfg-app-server")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-codex-1")
                .requestSource("UI")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-1");
        session.setUserId("user-1");
        session.setProviderType("codex-worker");

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX_APP_SERVER");

        when(sessionRepository.findById("session-codex-1")).thenReturn(Optional.of(session));
        when(llmModelManager.getModelConfig("cfg-app-server")).thenReturn(Optional.of(modelConfig));
        SessionProviderBoundMismatchException error = assertThrows(
                SessionProviderBoundMismatchException.class,
                () -> facade.resumeTask(request, context));

        assertEquals("codex-worker", error.getBoundProviderType());
        assertEquals("codex-app-server-worker", error.getRequestedProviderType());
        verify(taskQueryProvider, never()).resumeTask(anyString(), anyString(), any());
    }

    @Test
    void resumeTask_appServerSessionRejectsSdkModelConfig() {
        TypedTaskProvider appServerProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(appServerProvider));
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-app-server-1")
                .prompt("continue")
                .modelConfigId("cfg-sdk")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-app-server-1")
                .build();
        SessionEntity session = new SessionEntity();
        session.setId("session-app-server-1");
        session.setProviderType("codex-app-server-worker");
        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        when(sessionRepository.findById("session-app-server-1")).thenReturn(Optional.of(session));
        when(llmModelManager.getModelConfig("cfg-sdk")).thenReturn(Optional.of(modelConfig));
        SessionProviderBoundMismatchException error = assertThrows(
                SessionProviderBoundMismatchException.class,
                () -> facade.resumeTask(request, context));

        assertEquals("codex-app-server-worker", error.getBoundProviderType());
        assertEquals("codex-worker", error.getRequestedProviderType());
        verify(appServerProvider, never()).resumeTask(anyString(), anyString(), any());
    }

    @Test
    void resumeTask_rejectsExplicitProviderTypeThatConflictsWithSessionBoundProvider() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-codex-1")
                .prompt("continue")
                .providerType("claude-worker")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-codex-1")
                .requestSource("UI")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-1");
        session.setUserId("user-1");
        session.setProviderType("codex-worker");

        when(sessionRepository.findById("session-codex-1")).thenReturn(Optional.of(session));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.resumeTask(request, context));
        assertTrue(error.getMessage().contains("SESSION_PROVIDER_MISMATCH"));
        verify(taskQueryProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver, llmModelManager);
    }

    @Test
    void resumeTask_sdkSessionRejectsExplicitAppServerProvider() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-sdk-1")
                .prompt("continue")
                .providerType("codex-app-server-worker")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-sdk-1")
                .build();
        SessionEntity session = new SessionEntity();
        session.setId("session-sdk-1");
        session.setProviderType("codex-worker");
        when(sessionRepository.findById("session-sdk-1")).thenReturn(Optional.of(session));

        SessionProviderBoundMismatchException error = assertThrows(
                SessionProviderBoundMismatchException.class,
                () -> facade.resumeTask(request, context));

        assertEquals("codex-worker", error.getBoundProviderType());
        assertEquals("codex-app-server-worker", error.getRequestedProviderType());
        verify(taskQueryProvider, never()).resumeTask(anyString(), anyString(), any());
    }

    @Test
    void resumeTask_appServerSessionRejectsExplicitSdkProvider() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-app-1")
                .prompt("continue")
                .providerType("codex-worker")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-app-1")
                .build();
        SessionEntity session = new SessionEntity();
        session.setId("session-app-1");
        session.setProviderType("codex-app-server-worker");
        when(sessionRepository.findById("session-app-1")).thenReturn(Optional.of(session));

        SessionProviderBoundMismatchException error = assertThrows(
                SessionProviderBoundMismatchException.class,
                () -> facade.resumeTask(request, context));

        assertEquals("codex-app-server-worker", error.getBoundProviderType());
        assertEquals("codex-worker", error.getRequestedProviderType());
        verify(taskQueryProvider, never()).resumeTask(anyString(), anyString(), any());
    }

    @Test
    void listTasksPaged_prefersUnifiedSessionStoreWhenAvailable() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(facade, "workingDirectoryRepository", workingDirectoryRepository);

        SessionTaskEntity claudeTask = sessionTask(
                "task-claude-1", "session-claude-1", "claude-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 3, 24, 21, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );
        SessionTaskEntity codexTask = sessionTask(
                "task-codex-1", "session-codex-1", "codex-worker", "worker-1", "dir-2",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"codexThreadId\":\"thread-1\"}"
        );

        when(sessionTaskRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(codexTask, claudeTask));
        when(sessionRepository.findAllById(List.of("session-codex-1", "session-claude-1")))
                .thenReturn(List.of(
                        sessionEntity("session-codex-1", "user-1", "AWAITING_REPLY", LocalDateTime.of(2026, 3, 24, 22, 5)),
                        sessionEntity("session-claude-1", "user-1", "PROCESSING", LocalDateTime.of(2026, 3, 24, 21, 5))
                ));
        when(workingDirectoryRepository.findByDirectoryIdIn(List.of("dir-2")))
                .thenReturn(List.of(directoryEntity("dir-2", "Codex Project")));
        when(workingDirectoryRepository.findByDirectoryIdIn(List.of("dir-1")))
                .thenReturn(List.of(directoryEntity("dir-1", "Claude Project")));

        Object result = facade.listTasksPaged("user-1", 0, 20, null);

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        assertEquals(2L, page.get("totalSessions"));
        List<?> content = assertInstanceOf(List.class, page.get("content"));
        assertEquals(2, content.size());
        DispatchTaskDTO first = assertInstanceOf(DispatchTaskDTO.class, content.get(0));
        DispatchTaskDTO second = assertInstanceOf(DispatchTaskDTO.class, content.get(1));
        assertEquals("task-codex-1", first.getTaskId());
        assertEquals("thread-1", first.getCodexThreadId());
        assertEquals("Codex Project", first.getDirectoryName());
        assertEquals("task-claude-1", second.getTaskId());
        assertEquals("claude-session-1", second.getClaudeSessionId());
        assertEquals("Claude Project", second.getDirectoryName());
        verify(taskQueryProvider, never()).listTaskPage(anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void listTasksPaged_returnsOneSummaryTaskPerSession() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        SessionTaskEntity latestTask = sessionTask(
                "task-latest", "session-1", "codex-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        latestTask.setPrompt("latest prompt");
        latestTask.setCostUsd(new BigDecimal("2.000000"));
        latestTask.setInputTokens(20L);
        latestTask.setOutputTokens(30L);
        SessionTaskEntity firstTask = sessionTask(
                "task-first", "session-1", "codex-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 21, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        firstTask.setPrompt("first prompt");
        firstTask.setCostUsd(new BigDecimal("1.250000"));
        firstTask.setInputTokens(10L);
        firstTask.setOutputTokens(15L);

        when(sessionTaskRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(latestTask, firstTask));
        when(sessionRepository.findAllById(List.of("session-1")))
                .thenReturn(List.of(
                        sessionEntity("session-1", "user-1", "AWAITING_REPLY",
                                LocalDateTime.of(2026, 3, 24, 22, 5))
                ));

        Object result = facade.listTasksPaged("user-1", 0, 1, "AWAITING_REPLY");

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        assertEquals(1L, page.get("totalSessions"));
        List<?> content = assertInstanceOf(List.class, page.get("content"));
        assertEquals(1, content.size());
        DispatchTaskDTO summary = assertInstanceOf(DispatchTaskDTO.class, content.get(0));
        assertEquals("task-latest", summary.getTaskId());
        assertEquals(2, summary.getSessionTaskCount());
        assertEquals(new BigDecimal("3.250000"), summary.getSessionTotalCostUsd());
        assertEquals(30L, summary.getSessionInputTokens());
        assertEquals(45L, summary.getSessionOutputTokens());
        assertEquals("first prompt", summary.getSessionFirstPrompt());
    }

    @Test
    void listTasksPaged_compactOmitsHeavyFields() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        SessionTaskEntity latestTask = sessionTask(
                "task-latest", "session-1", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 22, 0),
                "{\"claudeSessionId\":\"claude-session-1\",\"checkpoints\":[{\"id\":\"ckpt-1\"}]}"
        );
        latestTask.setPrompt("latest prompt");
        latestTask.setResultText("large result payload");

        when(sessionTaskRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(latestTask));
        when(sessionRepository.findAllById(List.of("session-1")))
                .thenReturn(List.of(
                        sessionEntity("session-1", "user-1", "AWAITING_REPLY",
                                LocalDateTime.of(2026, 3, 24, 22, 5))
                ));

        Object result = facade.listTasksPaged("user-1", 0, 1, "AWAITING_REPLY", true);

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        List<?> content = assertInstanceOf(List.class, page.get("content"));
        Map<?, ?> summary = assertInstanceOf(Map.class, content.get(0));
        assertEquals("task-latest", summary.get("taskId"));
        assertEquals("claude-session-1", summary.get("claudeSessionId"));
        assertEquals("latest prompt", summary.get("sessionFirstPrompt"));
        assertFalse(summary.containsKey("resultText"));
        assertFalse(summary.containsKey("errorMessage"));
        assertFalse(summary.containsKey("checkpoints"));
    }

    @Test
    void listActiveTasks_allowsNullDirectoryId() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(facade, "workingDirectoryRepository", workingDirectoryRepository);

        SessionTaskEntity task = sessionTask(
                "task-claude-1", "session-claude-1", "claude-worker", "worker-1", null,
                "RUNNING", LocalDateTime.of(2026, 3, 24, 21, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );

        when(sessionTaskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                "user-1", List.of("RUNNING", "AWAITING_PERMISSION", "AWAITING_INPUT")))
                .thenReturn(List.of(task));
        when(sessionRepository.findAllById(List.of("session-claude-1")))
                .thenReturn(List.of(
                        sessionEntity("session-claude-1", "user-1", "PROCESSING",
                                LocalDateTime.of(2026, 3, 24, 21, 5))
                ));

        List<DispatchTaskDTO> tasks = facade.listActiveTasks("user-1");

        assertEquals(1, tasks.size());
        assertNull(tasks.get(0).getDirectoryId());
        assertNull(tasks.get(0).getDirectoryName());
        assertEquals("claude-session-1", tasks.get(0).getClaudeSessionId());
    }

    @Test
    void searchSessions_prefersUnifiedSessionStoreWhenAvailable() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        SessionTaskEntity matchingTask = sessionTask(
                "task-claude-1", "session-claude-1", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 21, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );
        matchingTask.setPrompt("Fix auth flow");
        matchingTask.setResultText("Auth flow fixed");
        matchingTask.setCostUsd(new BigDecimal("1.250000"));

        SessionTaskEntity otherTask = sessionTask(
                "task-codex-1", "session-codex-1", "codex-worker", "worker-2", "dir-2",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 20, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        otherTask.setPrompt("Unrelated prompt");

        when(sessionTaskRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(matchingTask, otherTask));
        when(sessionRepository.findAllById(List.of("session-claude-1", "session-codex-1")))
                .thenReturn(List.of(
                        sessionEntity("session-claude-1", "user-1", "AWAITING_REPLY", LocalDateTime.of(2026, 3, 24, 21, 10), "Auth Session", "[\"auth\",\"backend\"]"),
                        sessionEntity("session-codex-1", "user-1", "AWAITING_REPLY", LocalDateTime.of(2026, 3, 24, 20, 10), "Other", "[\"misc\"]")
                ));

        Object result = facade.searchSessions("user-1", "auth", null, null, 0, 20);

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        assertEquals(1L, page.get("total"));
        List<?> results = assertInstanceOf(List.class, page.get("results"));
        Map<?, ?> first = assertInstanceOf(Map.class, results.get(0));
        assertEquals("session-claude-1", first.get("sessionId"));
        assertEquals("Auth Session", first.get("customTitle"));
        assertEquals("task-claude-1", first.get("latestTaskId"));
        assertEquals(new BigDecimal("1.250000"), first.get("totalCost"));
        verify(taskQueryProvider, never()).searchSessionPage(anyString(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void getTask_prefersUnifiedSessionStoreWhenAvailable() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-codex-1", "session-codex-1", "codex-worker", "worker-1", "dir-2",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"codexThreadId\":\"thread-1\"}"
        );

        when(sessionTaskRepository.findByTaskIdAndUserId("task-codex-1", "user-1"))
                .thenReturn(Optional.of(task));

        Optional<DispatchTaskDTO> result = facade.getTask("task-codex-1", AgentResolveContext.builder()
                .userId("user-1")
                .build());

        assertEquals(true, result.isPresent());
        assertEquals("thread-1", result.orElseThrow().getCodexThreadId());
        verify(taskQueryProvider, never()).getTaskByIdAndUser(anyString(), anyString());
    }

    @Test
    void respondToTask_routesViaUnifiedSessionStoreProviderType() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-codex-1", "session-codex-1", "codex-worker", "worker-1", "dir-2",
                "AWAITING_PERMISSION", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        when(sessionTaskRepository.findByTaskId("task-codex-1")).thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");

        facade.respondToTask("task-codex-1", uiContext("user-1"), Map.of("decision", "approve"));

        verify(taskQueryProvider).respondToTask("task-codex-1", "user-1", Map.of("decision", "approve"));
        verify(taskQueryProvider, never()).getTaskById("task-codex-1");
    }

    @Test
    void rewindTask_routesViaUnifiedSessionStoreProviderTypeAndReturnsProviderPayload() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-claude-1", "session-claude-1", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );
        when(sessionTaskRepository.findByTaskId("task-claude-1")).thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.rewindTask(eq("task-claude-1"), eq("user-1"), any()))
                .thenReturn(Map.of("status", "rewound", "taskId", "task-claude-1"));

        Object result = facade.rewindTask(
                "task-claude-1", uiContext("user-1"), Map.of("mode", "conversation_fork"));

        assertEquals(Map.of("status", "rewound", "taskId", "task-claude-1"), result);
        verify(taskQueryProvider).rewindTask("task-claude-1", "user-1", Map.of("mode", "conversation_fork"));
        verify(taskQueryProvider, never()).getTaskById("task-claude-1");
    }

    @Test
    void reconnectTask_routesViaUnifiedSessionStoreProviderType() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-codex-reconnect", "session-codex-1", "codex-worker", "worker-1", "dir-2",
                "RUNNING", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        when(sessionTaskRepository.findByTaskId("task-codex-reconnect")).thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");

        facade.reconnectTask("task-codex-reconnect", uiContext("user-1"));

        verify(taskQueryProvider).reconnectTask("task-codex-reconnect", "user-1");
        verify(taskQueryProvider, never()).getTaskById("task-codex-reconnect");
    }

    @Test
    void resyncTask_routesViaUnifiedSessionStoreProviderTypeAndReturnsProviderPayload() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-claude-resync", "session-claude-1", "claude-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );
        when(sessionTaskRepository.findByTaskId("task-claude-resync")).thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.resyncTask("task-claude-resync", "user-1"))
                .thenReturn(Map.of("status", "synced", "taskId", "task-claude-resync"));

        Object result = facade.resyncTask("task-claude-resync", uiContext("user-1"));

        assertEquals(Map.of("status", "synced", "taskId", "task-claude-resync"), result);
        verify(taskQueryProvider).resyncTask("task-claude-resync", "user-1");
        verify(taskQueryProvider, never()).getTaskById("task-claude-resync");
    }

    @Test
    void scanCheckpoints_routesViaUnifiedSessionStoreProviderTypeAndReturnsProviderPayload() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-claude-checkpoints", "session-claude-1", "claude-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 3, 24, 22, 0), "{\"claudeSessionId\":\"claude-session-1\"}"
        );
        when(sessionTaskRepository.findByTaskId("task-claude-checkpoints")).thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.scanCheckpoints("task-claude-checkpoints", "user-1"))
                .thenReturn(Map.of("checkpoints", List.of(Map.of("id", "ckpt-1"))));

        Object result = facade.scanCheckpoints("task-claude-checkpoints", uiContext("user-1"));

        assertEquals(Map.of("checkpoints", List.of(Map.of("id", "ckpt-1"))), result);
        verify(taskQueryProvider).scanCheckpoints("task-claude-checkpoints", "user-1");
        verify(taskQueryProvider, never()).getTaskById("task-claude-checkpoints");
    }

    private SessionTaskEntity sessionTask(String taskId, String sessionId, String providerType,
                                          String workerId, String directoryId, String status,
                                          LocalDateTime createdAt, String taskStateJson) {
        SessionTaskEntity entity = new SessionTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setProviderType(providerType);
        entity.setWorkerId(workerId);
        entity.setDirectoryId(directoryId);
        entity.setUserId("user-1");
        entity.setPrompt(taskId + " prompt");
        entity.setStatus(status);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt.plusMinutes(1));
        entity.setTaskStateJson(taskStateJson);
        return entity;
    }

    private SessionEntity sessionEntity(String sessionId, String userId, String interactionState, LocalDateTime lastActivityAt) {
        return sessionEntity(sessionId, userId, interactionState, lastActivityAt, null, null);
    }

    private SessionEntity sessionEntity(String sessionId, String userId, String interactionState,
                                        LocalDateTime lastActivityAt, String title, String tagsJson) {
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId(userId);
        entity.setInteractionState(interactionState);
        entity.setLastActivityAt(lastActivityAt);
        entity.setUpdatedAt(lastActivityAt);
        entity.setTitle(title);
        entity.setTagsJson(tagsJson);
        return entity;
    }

    private WorkingDirectoryEntity directoryEntity(String directoryId, String projectName) {
        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(directoryId);
        entity.setProjectName(projectName);
        return entity;
    }

    // ── New tests ──

    @Test
    void createTask_withoutAgentOrProviderContext_throwsException() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-missing")
                .prompt("hi")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        assertThrows(IllegalArgumentException.class, () -> facade.createTask(request, context));
        verifyNoInteractions(agentResolver);
    }

    @Test
    void getTask_notFoundInAllSources_returnsEmpty() {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .build();

        // sessionTaskRepository is null (not injected)
        when(taskQueryProvider.getTaskByIdAndUser("task-missing", "user-1")).thenReturn(Optional.empty());

        Optional<DispatchTaskDTO> result = facade.getTask("task-missing", context);

        assertTrue(result.isEmpty());
    }

    @Test
    void getTask_rejectsUnownedResourceBeforeRepositoryOrProviderLookup() {
        AgentResolveContext context = uiContext("attacker");
        doThrow(new SecurityException("Resource access denied"))
                .when(resourceAccessService)
                .requireOwnedTask("task-private", "attacker", "tenant-1");

        assertThrows(SecurityException.class, () -> facade.getTask("task-private", context));

        verifyNoInteractions(taskQueryProvider);
    }

    @Test
    void listTasksBySession_authorizesParentBeforeAnyChildQuery() {
        AgentResolveContext context = uiContext("user-1");

        assertEquals(List.of(), facade.listTasksBySession("session-1", context));

        InOrder ordered = inOrder(resourceAccessService, sessionRepository);
        ordered.verify(resourceAccessService)
                .requireOwnedSession("session-1", "user-1", "tenant-1");
        ordered.verify(sessionRepository).findById("session-1");
    }

    @Test
    void listTasksBySession_primaryProjectionUsesUserAndTenantScope() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        AgentResolveContext context = uiContext("user-1");
        SessionTaskEntity task = sessionTask(
                "task-1", "session-1", "codex-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 7, 14, 16, 0), null);
        task.setTenantId("tenant-1");
        when(sessionTaskRepository.findBySessionIdAndUserIdAndTenantIdOrderByCreatedAtDesc(
                "session-1", "user-1", "tenant-1"))
                .thenReturn(List.of(task));

        List<DispatchTaskDTO> result = facade.listTasksBySession("session-1", context);

        assertEquals(List.of("task-1"), result.stream().map(DispatchTaskDTO::getTaskId).toList());
        InOrder ordered = inOrder(resourceAccessService, sessionTaskRepository);
        ordered.verify(resourceAccessService)
                .requireOwnedSession("session-1", "user-1", "tenant-1");
        ordered.verify(sessionTaskRepository)
                .findBySessionIdAndUserIdAndTenantIdOrderByCreatedAtDesc(
                        "session-1", "user-1", "tenant-1");
        verify(sessionTaskRepository, never())
                .findBySessionIdAndUserIdOrderByCreatedAtDesc(anyString(), anyString());
    }

    @Test
    void respondToTask_rejectsUnownedResourceBeforeProviderRouting() {
        AgentResolveContext context = uiContext("attacker");
        doThrow(new SecurityException("Resource access denied"))
                .when(resourceAccessService)
                .requireOwnedTask("task-private", "attacker", "tenant-1");

        assertThrows(SecurityException.class,
                () -> facade.respondToTask("task-private", context, Map.of("decision", "approve")));

        verifyNoInteractions(taskQueryProvider);
    }

    @Test
    void taskMutationsRejectUnownedResourceBeforeProviderRouting() {
        AgentResolveContext context = uiContext("attacker");
        doThrow(new SecurityException("Resource access denied"))
                .when(resourceAccessService)
                .requireOwnedTask("task-private", "attacker", "tenant-1");

        assertThrows(SecurityException.class,
                () -> facade.reconnectTask("task-private", context));
        assertThrows(SecurityException.class,
                () -> facade.resyncTask("task-private", context));
        assertThrows(SecurityException.class,
                () -> facade.rewindTask("task-private", context, Map.of()));
        assertThrows(SecurityException.class,
                () -> facade.scanCheckpoints("task-private", context));
        assertThrows(SecurityException.class,
                () -> facade.deleteTask("task-private", context));

        verify(resourceAccessService, times(5))
                .requireOwnedTask("task-private", "attacker", "tenant-1");
        verifyNoInteractions(taskQueryProvider);
    }

    @Test
    void resumeTask_rejectsUnownedSessionBeforeProviderRouting() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-private")
                .providerType("claude-worker")
                .prompt("continue")
                .build();
        AgentResolveContext context = uiContext("attacker");
        doThrow(new SecurityException("Resource access denied"))
                .when(resourceAccessService)
                .requireOwnedSession("session-private", "attacker", "tenant-1");

        assertThrows(SecurityException.class, () -> facade.resumeTask(request, context));

        verifyNoInteractions(taskQueryProvider);
    }

    @Test
    void cancelTask_routesViaSessionStore() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-cancel-1")
                .agentId("agent-1")
                .status("RUNNING")
                .build();
        when(taskQueryProvider.getTaskByIdAndUser("task-cancel-1", "user-1"))
                .thenReturn(Optional.of(task));
        when(agentResolver.resolveAgent(eq("agent-1"), any())).thenReturn(Optional.of(agent));

        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        facade.cancelTask("task-cancel-1", "agent-1", context);

        verify(agent).cancelTask("task-cancel-1");
    }

    @Test
    void cancelTask_routesViaProviderWhenAgentIdIsProviderConstant() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-claude-direct-1")
                .agentId("claude-worker")
                .providerType("claude-worker")
                .status("RUNNING")
                .build();
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.getTaskByIdAndUser("task-claude-direct-1", "user-1"))
                .thenReturn(Optional.of(task));

        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        facade.cancelTask("task-claude-direct-1", "claude-worker", context);

        verify(taskQueryProvider).cancelTaskDirect("task-claude-direct-1", "user-1");
        verify(agentResolver, never()).resolveAgent(eq("claude-worker"), any());
        verify(agent, never()).cancelTask(anyString());
    }

    @Test
    void cancelTask_routesOwnerForceOnlyThroughAuthorizedProviderProjection() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-claude-force-1")
                .agentId("agent-claude-owner")
                .providerType("claude-worker")
                .status("CANCEL_REQUESTED")
                .build();
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.getTaskByIdAndUser("task-claude-force-1", "user-1"))
                .thenReturn(Optional.of(task));
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        facade.cancelTask("task-claude-force-1", "attacker-route", context, true);

        verify(taskQueryProvider).cancelTaskDirect("task-claude-force-1", "user-1", true);
        verify(agentResolver, never()).resolveAgent(anyString(), any());
    }

    @Test
    void cancelTask_routesViaProviderWhenUnifiedTaskHasLogicalAgentId() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-codex-logical-agent", "session-codex-1", "codex-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 3, 27, 10, 0), "{\"codexThreadId\":\"thread-1\"}"
        );
        task.setAgentId("agent-codex-prod-1");

        when(sessionTaskRepository.findByTaskIdAndUserId("task-codex-logical-agent", "user-1"))
                .thenReturn(Optional.of(task));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");

        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        facade.cancelTask("task-codex-logical-agent", task.getAgentId(), context);

        verify(taskQueryProvider).cancelTaskDirect("task-codex-logical-agent", "user-1");
        verify(agentResolver, never()).resolveAgent(eq("agent-codex-prod-1"), any());
        verify(agent, never()).cancelTask(anyString());
    }

    @Test
    void cancelTask_mappedAppServerProviderMissing_doesNotFallbackToSdkProvider() {
        TypedTaskProvider sdkProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(sdkProvider));
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-app-server", "session-app-server", "codex-app-server-worker", "worker-1", "dir-1",
                "RUNNING", LocalDateTime.of(2026, 7, 12, 10, 0), "{}");

        when(sessionTaskRepository.findByTaskIdAndUserId("task-app-server", "user-1"))
                .thenReturn(Optional.of(task));
        when(sdkProvider.getProviderType()).thenReturn("codex-worker");
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .build();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> facade.cancelTask("task-app-server", null, context));

        assertEquals("Provider not found: codex-app-server-worker", error.getMessage());
        verify(sdkProvider, never()).cancelTaskDirect(anyString(), anyString());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void cancelTask_routesViaProviderWhenLogicalAgentIsMissing() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-direct-no-agent")
                .providerType("claude-worker")
                .status("RUNNING")
                .build();
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.getTaskByIdAndUser("task-direct-no-agent", "user-1"))
                .thenReturn(Optional.of(task));

        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        facade.cancelTask("task-direct-no-agent", null, context);

        verify(taskQueryProvider).cancelTaskDirect("task-direct-no-agent", "user-1");
        verifyNoInteractions(agentResolver, agent);
    }

    @Test
    void canonicalTerminationPlanCapturesExactProviderAndExecutesItOnce() {
        DispatchTaskDTO task = canonicalTerminationTask("RUNNING", "codex-worker", "agent-1");
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.getTaskByIdAndUser("task-canonical-1", "user-1"))
                .thenReturn(Optional.of(task));
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                facade.resolveTerminationExecutionPlan(
                        "task-canonical-1", context, false);
        TaskTerminationCommandCoordinator.Outcome outcome =
                executeCanonicalTermination(plan).outcome();

        assertEquals(TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED,
                outcome.safeCode());
        assertNull(outcome.terminalStatus());
        assertEquals("provider-task-1", plan.identity().providerTaskId());
        assertEquals("runtime-1", plan.identity().runtimeId());
        assertEquals(3, plan.identity().runtimeRevision());
        assertEquals(7L, plan.identity().routingEpoch());
        verify(taskQueryProvider).cancelTaskDirect("task-canonical-1", "user-1");
        verify(agentResolver, never()).resolveAgent(anyString(), any());
    }

    @Test
    void canonicalTerminationPlanDriftFailsBeforeCapturedProviderEffect() {
        DispatchTaskDTO original = canonicalTerminationTask(
                "RUNNING", "codex-worker", "agent-1");
        DispatchTaskDTO drifted = canonicalTerminationTask(
                "RUNNING", "codex-worker", "agent-1");
        drifted.setWorkerId("worker-other");
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.getTaskByIdAndUser("task-canonical-1", "user-1"))
                .thenReturn(Optional.of(original), Optional.of(drifted));
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                facade.resolveTerminationExecutionPlan(
                        "task-canonical-1", context, false);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executeCanonicalTermination(plan));

        assertEquals("TERMINATION_PLAN_IDENTITY_CONFLICT", failure.getMessage());
        verify(taskQueryProvider, never()).cancelTaskDirect(anyString(), anyString());
        verifyNoInteractions(agentResolver, agent);
    }

    @Test
    void canonicalTerminationTerminalNoOpDoesNotRequireProviderAvailability() {
        DispatchTaskDTO task = canonicalTerminationTask(
                "COMPLETED", "unavailable-provider", "agent-1");
        when(taskQueryProvider.getTaskByIdAndUser("task-canonical-1", "user-1"))
                .thenReturn(Optional.of(task));
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                facade.resolveTerminationExecutionPlan(
                        "task-canonical-1", context, false);
        TaskTerminationCommandCoordinator.Outcome outcome =
                executeCanonicalTermination(plan).outcome();

        assertEquals("TASK_ALREADY_TERMINAL_COMPLETED", outcome.safeCode());
        assertEquals("COMPLETED", outcome.terminalStatus());
        verify(taskQueryProvider, never()).getProviderType();
        verify(taskQueryProvider, never()).cancelTaskDirect(anyString(), anyString());
        verifyNoInteractions(agentResolver, agent);

        reset(taskQueryProvider, agentResolver, agent);
        DispatchTaskDTO terminalLegacy = canonicalTerminationTask("ABORTED", null, null);
        when(taskQueryProvider.getTaskByIdAndUser("task-canonical-1", "user-1"))
                .thenReturn(Optional.of(terminalLegacy));
        TaskTerminationCommandCoordinator.TerminationExecutionPlan legacyPlan =
                facade.resolveTerminationExecutionPlan(
                        "task-canonical-1", context, true);
        TaskTerminationCommandCoordinator.Outcome legacyOutcome =
                executeCanonicalTermination(legacyPlan).outcome();
        assertEquals("TASK_ALREADY_TERMINAL_ABORTED", legacyOutcome.safeCode());
        verifyNoInteractions(agentResolver, agent);
        verify(taskQueryProvider, never()).cancelTaskDirect(anyString(), anyString());
    }

    @Test
    void canonicalTerminationCapturesA2aRouteWithoutProviderFallback() {
        DispatchTaskDTO task = canonicalTerminationTask("RUNNING", null, "agent-a2a");
        when(taskQueryProvider.getTaskByIdAndUser("task-canonical-1", "user-1"))
                .thenReturn(Optional.of(task));
        when(agentResolver.resolveAgent(eq("agent-a2a"), any()))
                .thenReturn(Optional.of(agent));
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                facade.resolveTerminationExecutionPlan(
                        "task-canonical-1", context, false);
        TaskTerminationCommandCoordinator.Outcome outcome =
                executeCanonicalTermination(plan).outcome();

        assertEquals(TaskTerminationCommandCoordinator.ExecutionRoute.A2A,
                plan.identity().executionRoute());
        assertEquals(TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED,
                outcome.safeCode());
        verify(agent).cancelTask("task-canonical-1");
        verify(taskQueryProvider, never()).cancelTaskDirect(anyString(), anyString());
    }

    @Test
    void canonicalTerminationRejectsA2aForceBeforeAgentOrProviderEffect() {
        DispatchTaskDTO task = canonicalTerminationTask("RUNNING", null, "agent-a2a");
        when(taskQueryProvider.getTaskByIdAndUser("task-canonical-1", "user-1"))
                .thenReturn(Optional.of(task));
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> facade.resolveTerminationExecutionPlan(
                        "task-canonical-1", context, true));

        assertEquals(
                "force cancel not supported by the A2A route for task task-canonical-1",
                failure.getMessage());
        verifyNoInteractions(agentResolver, agent);
        verify(taskQueryProvider, never()).cancelTaskDirect(anyString(), anyString());
    }

    @Test
    void createTask_codexDirectRouteNotSkippedBySessionId() {
        // Codex 任务即使带 sessionId 也应走 direct route（非 claude-worker 不需要 A2a 解析）
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .directoryId("dir-1")
                .sessionId("session-1") // 关键：带 sessionId
                .prompt("codex task")
                .model("gpt-5.4")
                .modelConfigId("cfg-codex")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1").tenantId("tenant-1").requestSource("UI").build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-direct").providerType("codex-worker").build();

        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1"))).thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-direct", result.getTaskId());
        // 不应走 agentResolver（那是 A2a 路径）
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void cancelTask_failsFastWhenAgentNotResolvable() {
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-codex-1")
                .agentId("codex-worker")
                .status("RUNNING")
                .build();
        when(taskQueryProvider.getTaskByIdAndUser("task-codex-1", "user-1"))
                .thenReturn(Optional.of(task));
        when(agentResolver.resolveAgent(eq("codex-worker"), any())).thenReturn(Optional.empty());

        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1").tenantId("tenant-1").requestSource("UI").build();

        // 不再 fallback 到 Provider，直接 fail-fast
        assertThrows(IllegalArgumentException.class,
                () -> facade.cancelTask("task-codex-1", "codex-worker", context));

        verify(agent, never()).cancelTask(anyString());
    }

    private DispatchTaskDTO canonicalTerminationTask(
            String status,
            String providerType,
            String agentId) {
        return DispatchTaskDTO.builder()
                .taskId("task-canonical-1")
                .workerTaskId("provider-task-1")
                .runtimeId("runtime-1")
                .runtimeRevision(3)
                .runtimeType("CODEX")
                .runtimeInstanceId("instance-1")
                .routingEpoch(7L)
                .sessionId("session-1")
                .workerId("worker-1")
                .userId("user-1")
                .agentId(agentId)
                .providerType(providerType)
                .directoryId("directory-1")
                .status(status)
                .model("gpt-5.4")
                .modelConfigId("model-config-1")
                .build();
    }

    @Test
    void listWorkerSessions_delegatesToProvider() {
        List<WorkerSessionSummary> sessions = WorkerSessionSummary.fromList(List.of(
                Map.of("sessionId", "s1", "status", "active"),
                Map.of("sessionId", "s2", "status", "completed")
        ));
        when(taskQueryProvider.listWorkerSessionSummaries("worker-1", "user-1")).thenReturn(sessions);

        List<Map<String, Object>> result = facade.listWorkerSessions("worker-1", "user-1");

        assertEquals(2, result.size());
        assertEquals("s1", result.get(0).get("sessionId"));
        verify(taskQueryProvider).listWorkerSessionSummaries("worker-1", "user-1");
    }

    @Test
    void listWorkerSessions_usesDedicatedWorkerSessionProviderList() {
        facade = new TaskDispatchFacade(
                agentResolver,
                bindingService,
                sessionRepository,
                resourceAccessService,
                List.of(),
                List.of(),
                List.of(),
                List.of(new WorkerSessionOnlyProvider()),
                llmModelManager);

        List<Map<String, Object>> result = facade.listWorkerSessions("worker-1", "user-1");

        assertEquals(1, result.size());
        assertEquals("worker-only-session", result.get(0).get("sessionId"));
        verifyNoInteractions(taskQueryProvider);
    }

    @Test
    void listWorkerSessions_skipsProviderWhenWorkerBelongsToAnotherBackend() {
        TypedTaskProvider claudeProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider langgraphProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(claudeProvider, langgraphProvider));

        List<Map<String, Object>> sessions = List.of(Map.of("session_id", "lg-session-1"));
        when(claudeProvider.listWorkerSessionSummaries("lg-worker-1", "user-1"))
                .thenThrow(new IllegalArgumentException("Worker not found: lg-worker-1"));
        when(langgraphProvider.listWorkerSessionSummaries("lg-worker-1", "user-1"))
                .thenReturn(WorkerSessionSummary.fromList(sessions));

        List<Map<String, Object>> result = facade.listWorkerSessions("lg-worker-1", "user-1");

        assertEquals(1, result.size());
        assertEquals("lg-session-1", result.get(0).get("session_id"));
    }

    @Test
    void getWorkerSessionMessageCount_skipsProviderWhenWorkerBelongsToAnotherBackend() {
        TypedTaskProvider claudeProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider langgraphProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(claudeProvider, langgraphProvider));

        Map<String, Object> count = Map.of("user_count", 1, "assistant_count", 1, "total", 2);
        when(claudeProvider.getWorkerSessionMessageCountResult("lg-worker-1", "session-1", "user-1"))
                .thenThrow(new IllegalArgumentException("Worker not found"));
        when(langgraphProvider.getWorkerSessionMessageCountResult("lg-worker-1", "session-1", "user-1"))
                .thenReturn(WorkerSessionMessageCount.from(count));

        Map<String, Object> result = facade.getWorkerSessionMessageCount("lg-worker-1", "session-1", "user-1");

        assertEquals(count, result);
    }

    @Test
    void getWorkerSessionMessages_skipsProviderWhenWorkerBelongsToAnotherBackend() {
        TypedTaskProvider claudeProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider langgraphProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(claudeProvider, langgraphProvider));

        List<Map<String, Object>> messages = List.of(Map.of("role", "assistant", "content", "ok"));
        when(claudeProvider.listWorkerSessionMessages("lg-worker-1", "session-1", "user-1", 0, 50))
                .thenThrow(new IllegalArgumentException("Worker not found"));
        when(langgraphProvider.listWorkerSessionMessages("lg-worker-1", "session-1", "user-1", 0, 50))
                .thenReturn(WorkerSessionMessage.fromList(messages));

        List<Map<String, Object>> result =
                facade.getWorkerSessionMessages("lg-worker-1", "session-1", "user-1", 0, 50);

        assertEquals(messages, result);
    }

    @Test
    void syncWorkerSessions_delegatesToProvider() {
        Map<String, Object> syncResult = Map.of("synced", 5, "workerId", "worker-1");
        when(taskQueryProvider.syncWorkerSessionState("worker-1", "user-1", "tenant-1"))
                .thenReturn(WorkerSessionSyncResult.from(syncResult));

        Map<String, Object> result = facade.syncWorkerSessions("worker-1", "user-1", "tenant-1");

        assertEquals(5, result.get("synced"));
        verify(taskQueryProvider).syncWorkerSessionState("worker-1", "user-1", "tenant-1");
    }

    @Test
    void syncWorkerSessions_skipsProviderWhenWorkerBelongsToAnotherBackend() {
        TypedTaskProvider claudeProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider langgraphProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(claudeProvider, langgraphProvider));

        Map<String, Object> syncResult = Map.of("synced", 0, "total", 1);
        when(claudeProvider.syncWorkerSessionState("lg-worker-1", "user-1", "tenant-1"))
                .thenThrow(new IllegalArgumentException("Worker not found"));
        when(langgraphProvider.syncWorkerSessionState("lg-worker-1", "user-1", "tenant-1"))
                .thenReturn(WorkerSessionSyncResult.from(syncResult));

        Map<String, Object> result = facade.syncWorkerSessions("lg-worker-1", "user-1", "tenant-1");

        assertEquals(syncResult, result);
    }

    @Test
    void workerSessionProvidersUseTypedContracts() {
        TypedWorkerSessionProvider provider = new TypedWorkerSessionProvider();

        assertEquals("legacy-session", provider.listWorkerSessionSummaries("worker-1", "user-1")
                .get(0).sessionId());
        assertEquals(2L, provider.getWorkerSessionMessageCountResult("worker-1", "legacy-session", "user-1")
                .total());
        assertEquals("assistant", provider.listWorkerSessionMessages("worker-1", "legacy-session", "user-1", 0, 10)
                .get(0).role());
        assertEquals(1L, provider.syncWorkerSessionState("worker-1", "user-1", "tenant-1").total());
    }

    @Test
    void resumeTask_usesExplicitCodexBizProviderWithOpenAICodexModelConfigWhenSessionIsUnbound() {
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-biz-worker")
                .workerId("worker-1")
                .sessionId("session-legacy-codex-biz")
                .prompt("continue actor task")
                .modelConfigId("cfg-codex")
                .metadata(Map.of("codexHomeKey", "tenant/world-sim/scenario-1/actor-1"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-legacy-codex-biz")
                .requestSource("WORLD_SIM")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-legacy-codex-biz");
        session.setUserId("user-1");
        when(sessionRepository.findById("session-legacy-codex-biz")).thenReturn(Optional.of(session));

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-resumed")
                .providerType("codex-biz-worker")
                .sessionId("session-legacy-codex-biz")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-codex-biz-resumed", result.getTaskId());
        assertEquals("codex-biz-worker", result.getProviderType());
        verify(codexBizProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("codexHomeKey"))
                        && "cfg-codex".equals(params.get("modelConfigId"))
                        && "session-legacy-codex-biz".equals(params.get("sessionId"))));
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void resumeTask_keepsCodexBizMetadataWhenSessionIsBoundToCodexBizProvider() {
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("codex_home_key", "tenant/world-sim/scenario-1/actor-2");
        metadata.put("developer_instructions", "Return only valid JSON.");
        metadata.put("codex_policy", Map.of(
                "sandbox_mode", "workspace-write",
                "approval_policy", "never",
                "network_access_enabled", false,
                "web_search_mode", "disabled"));

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-codex-biz-bound")
                .prompt("continue actor task")
                .modelConfigId("cfg-codex")
                .metadata(metadata)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-codex-biz-bound")
                .requestSource("WORLD_SIM")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-biz-bound");
        session.setUserId("user-1");
        session.setProviderType("codex-biz-worker");
        when(sessionRepository.findById("session-codex-biz-bound")).thenReturn(Optional.of(session));

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-bound")
                .providerType("codex-biz-worker")
                .sessionId("session-codex-biz-bound")
                .build();

        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(codexBizProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-codex-biz-bound", result.getTaskId());
        assertEquals("codex-biz-worker", result.getProviderType());
        verify(codexBizProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "tenant/world-sim/scenario-1/actor-2".equals(params.get("codex_home_key"))
                        && "Return only valid JSON.".equals(params.get("developer_instructions"))
                        && metadata.get("codex_policy").equals(params.get("codex_policy"))
                        && "cfg-codex".equals(params.get("modelConfigId"))));
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void createTask_withKnownContextIdAndResumeFlagContinuesBoundCodexBizSessionWithoutProviderType() {
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("ctx-codex-biz-1")
                .prompt("continue actor task")
                .resume(true)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        AgentConversationContextEntity boundContext = new AgentConversationContextEntity();
        boundContext.setContextId("ctx-codex-biz-1");
        boundContext.setUserId("user-1");
        boundContext.setTargetAgentId("world-sim-agent");
        boundContext.setAgentType("codex-biz-worker");
        boundContext.setNavigatorSessionId("session-codex-biz-bound");

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-biz-bound");
        session.setUserId("user-1");
        session.setAgentId("world-sim-agent");
        session.setProviderType("codex-biz-worker");
        session.setCurrentWorkerId("worker-1");
        session.setCurrentDirectoryId("dir-1");
        session.setProviderStateJson(ProviderStateCodec.mergeSessionValues(
                null,
                "codex-biz-worker",
                Map.of(
                        ProviderStateCodec.FIELD_CODEX_HOME_KEY, "tenant/world-sim/scenario-1/home-1",
                        ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID, "tenant/world-sim/scenario-1/actor-1")));

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-biz-context")
                .providerType("codex-biz-worker")
                .sessionId("session-codex-biz-bound")
                .agentId("world-sim-agent")
                .contextId("ctx-codex-biz-1")
                .build();

        when(agentConversationContextRepository.findByContextIdAndUserId("ctx-codex-biz-1", "user-1"))
                .thenReturn(Optional.of(boundContext));
        when(agentConversationContextRepository.findById("ctx-codex-biz-1")).thenReturn(Optional.of(boundContext));
        when(sessionRepository.findById("session-codex-biz-bound")).thenReturn(Optional.of(session));
        when(codexProvider.getProviderType()).thenReturn("codex-worker");
        when(codexBizProvider.getProviderType()).thenReturn("codex-biz-worker");
        when(codexBizProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-biz-context", result.getTaskId());
        verify(codexBizProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "codex-biz-worker".equals(params.get("providerType"))
                        && "session-codex-biz-bound".equals(params.get("sessionId"))
                        && "worker-1".equals(params.get("workerId"))
                        && "dir-1".equals(params.get("directoryId"))
                        && "ctx-codex-biz-1".equals(params.get("contextId"))
                        && "tenant/world-sim/scenario-1/home-1".equals(params.get("codexHomeKey"))
                        && "tenant/world-sim/scenario-1/actor-1".equals(params.get("privateAccountId"))));
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void createTask_withKnownContextRejectsUnownedBoundSessionBeforeRepositoryOrProvider() {
        TypedTaskProvider codexBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("ctx-owned-context")
                .prompt("continue")
                .resume(true)
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("OPEN_API")
                .build();

        AgentConversationContextEntity boundContext = new AgentConversationContextEntity();
        boundContext.setContextId("ctx-owned-context");
        boundContext.setUserId("user-1");
        boundContext.setTargetAgentId("agent-1");
        boundContext.setNavigatorSessionId("session-owned-by-other-tenant");

        when(agentConversationContextRepository.findByContextIdAndUserId("ctx-owned-context", "user-1"))
                .thenReturn(Optional.of(boundContext));
        doThrow(new SecurityException("Resource access denied"))
                .when(resourceAccessService)
                .requireOwnedSession("session-owned-by-other-tenant", "user-1", "tenant-1");

        SecurityException error = assertThrows(SecurityException.class,
                () -> facade.createTask(request, context));

        assertEquals("Resource access denied", error.getMessage());
        verify(sessionRepository, never()).findById("session-owned-by-other-tenant");
        verifyNoInteractions(codexBizProvider, agentResolver);
    }

    @Test
    void createTask_withKnownLangGraphBizContextCreatesDirectTaskWhenNotExplicitResume() {
        TypedTaskProvider langgraphBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(langgraphBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28")
                .prompt("BUG-148 smoke second 20260705-200741")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("agent-owner-1")
                .tenantId("tenant-1")
                .requestSource("OPEN_API")
                .build();

        AgentConversationContextEntity boundContext = new AgentConversationContextEntity();
        boundContext.setContextId("bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28");
        boundContext.setUserId("agent-owner-1");
        boundContext.setTargetAgentId("tms-tenant-88800-root-agent");
        boundContext.setAgentType("langgraph-biz-worker");
        boundContext.setNavigatorSessionId("session-langgraph-biz-bound");

        SessionEntity session = new SessionEntity();
        session.setId("session-langgraph-biz-bound");
        session.setUserId("agent-owner-1");
        session.setAgentId("tms-tenant-88800-root-agent");
        session.setProviderType("langgraph-biz-worker");
        session.setCurrentWorkerId("worker-langgraph-1");
        session.setCurrentDirectoryId("dir-langgraph-1");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("lgt_second")
                .providerType("langgraph-biz-worker")
                .sessionId("session-langgraph-biz-bound")
                .agentId("tms-tenant-88800-root-agent")
                .contextId("bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28")
                .workerId("worker-langgraph-1")
                .directoryId("dir-langgraph-1")
                .build();

        when(agentConversationContextRepository.findById("bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28"))
                .thenReturn(Optional.of(boundContext));
        when(agentConversationContextRepository.findByContextIdAndUserId(
                "bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28", "agent-owner-1"))
                .thenReturn(Optional.of(boundContext));
        when(sessionRepository.findById("session-langgraph-biz-bound")).thenReturn(Optional.of(session));
        when(langgraphBizProvider.getProviderType()).thenReturn("langgraph-biz-worker");
        when(sessionTaskRepository.findBySessionIdAndUserIdAndProviderTypeAndStatusInOrderByCreatedAtDesc(
                eq("session-langgraph-biz-bound"),
                eq("agent-owner-1"),
                eq("langgraph-biz-worker"),
                any()))
                .thenReturn(List.of());
        when(langgraphBizProvider.createTaskDirect(any(), eq("agent-owner-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("lgt_second", result.getTaskId());
        verify(langgraphBizProvider).createTaskDirect(
                argThat(params -> "langgraph-biz-worker".equals(params.get("providerType"))
                        && "session-langgraph-biz-bound".equals(params.get("sessionId"))
                        && "worker-langgraph-1".equals(params.get("workerId"))
                        && "dir-langgraph-1".equals(params.get("directoryId"))
                        && "tms-tenant-88800-root-agent".equals(params.get("agentId"))
                        && "bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28".equals(params.get("contextId"))
                        && "BUG-148 smoke second 20260705-200741".equals(params.get("prompt"))),
                eq("agent-owner-1"),
                eq("tenant-1"));
        verify(langgraphBizProvider, never()).resumeTask(anyString(), anyString(), any());
        verify(agentContextStore).saveSessionRefFull(
                "bctx_20260705_c0_c099d8fbf57e4762828a284c8198bb28",
                "langgraph-biz-worker",
                null,
                "session-langgraph-biz-bound",
                "agent-owner-1",
                "tms-tenant-88800-root-agent",
                null);
        verifyNoInteractions(agentResolver);
    }

    @Test
    void createTask_withKnownLangGraphBizContextRejectsDirectTaskWhenSessionHasActiveTask() {
        TypedTaskProvider langgraphBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(langgraphBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        String contextId = "bctx_20260705_e2_e277c060b1f64d93bd1477ef23f9eb2e";
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId(contextId)
                .prompt("immediate second ask")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("agent-owner-1")
                .tenantId("tenant-1")
                .requestSource("OPEN_API")
                .build();

        AgentConversationContextEntity boundContext = new AgentConversationContextEntity();
        boundContext.setContextId(contextId);
        boundContext.setUserId("agent-owner-1");
        boundContext.setTargetAgentId("tms-tenant-88800-root-agent");
        boundContext.setAgentType("langgraph-biz-worker");
        boundContext.setNavigatorSessionId("session-langgraph-biz-bound");

        SessionEntity session = new SessionEntity();
        session.setId("session-langgraph-biz-bound");
        session.setUserId("agent-owner-1");
        session.setAgentId("tms-tenant-88800-root-agent");
        session.setProviderType("langgraph-biz-worker");
        session.setCurrentWorkerId("worker-langgraph-1");
        session.setCurrentDirectoryId("dir-langgraph-1");

        SessionTaskEntity activeTask = sessionTask(
                "lgt_45e01f2e4dfd42e9",
                "session-langgraph-biz-bound",
                "langgraph-biz-worker",
                "worker-langgraph-1",
                "dir-langgraph-1",
                "RUNNING",
                LocalDateTime.of(2026, 7, 5, 22, 13, 26),
                "{}");
        activeTask.setUserId("agent-owner-1");

        when(agentConversationContextRepository.findById(contextId)).thenReturn(Optional.of(boundContext));
        when(agentConversationContextRepository.findByContextIdAndUserId(contextId, "agent-owner-1"))
                .thenReturn(Optional.of(boundContext));
        when(sessionRepository.findById("session-langgraph-biz-bound")).thenReturn(Optional.of(session));
        when(langgraphBizProvider.getProviderType()).thenReturn("langgraph-biz-worker");
        when(sessionTaskRepository.findBySessionIdAndUserIdAndProviderTypeAndStatusInOrderByCreatedAtDesc(
                eq("session-langgraph-biz-bound"),
                eq("agent-owner-1"),
                eq("langgraph-biz-worker"),
                any()))
                .thenReturn(List.of(activeTask));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> facade.createTask(request, context));

        assertTrue(error.getMessage().contains("CONTEXT_RUNTIME_BUSY"));
        assertTrue(error.getMessage().contains(contextId));
        assertTrue(error.getMessage().contains("lgt_45e01f2e4dfd42e9"));
        verify(langgraphBizProvider, never()).createTaskDirect(any(), anyString(), anyString());
        verify(langgraphBizProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void createTask_withKnownContextIdRejectsConflictingProviderType() {
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("ctx-codex-biz-1")
                .providerType("codex-worker")
                .prompt("continue actor task")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        AgentConversationContextEntity boundContext = new AgentConversationContextEntity();
        boundContext.setContextId("ctx-codex-biz-1");
        boundContext.setUserId("user-1");
        boundContext.setTargetAgentId("world-sim-agent");
        boundContext.setAgentType("codex-biz-worker");
        boundContext.setNavigatorSessionId("session-codex-biz-bound");

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-biz-bound");
        session.setProviderType("codex-biz-worker");

        when(agentConversationContextRepository.findByContextIdAndUserId("ctx-codex-biz-1", "user-1"))
                .thenReturn(Optional.of(boundContext));
        when(sessionRepository.findById("session-codex-biz-bound")).thenReturn(Optional.of(session));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));
        assertTrue(error.getMessage().contains("CONTEXT_WORKER_MISMATCH"));
        verify(codexBizProvider, never()).resumeTask(anyString(), anyString(), any());
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void createTask_withKnownContextIdRejectsConflictingCodexBizScopedHome() {
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        TypedTaskProvider codexBizProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexProvider, codexBizProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("ctx-codex-biz-1")
                .prompt("continue actor task")
                .metadata(Map.of("privateAccountId", "tenant/world-sim/scenario-1/actor-other"))
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("WORLD_SIM")
                .build();

        AgentConversationContextEntity boundContext = new AgentConversationContextEntity();
        boundContext.setContextId("ctx-codex-biz-1");
        boundContext.setUserId("user-1");
        boundContext.setTargetAgentId("world-sim-agent");
        boundContext.setAgentType("codex-biz-worker");
        boundContext.setNavigatorSessionId("session-codex-biz-bound");

        SessionEntity session = new SessionEntity();
        session.setId("session-codex-biz-bound");
        session.setProviderType("codex-biz-worker");
        session.setProviderStateJson(ProviderStateCodec.mergeSessionValues(
                null,
                "codex-biz-worker",
                Map.of(
                        ProviderStateCodec.FIELD_CODEX_HOME_KEY, "tenant/world-sim/scenario-1/home-1",
                        ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID, "tenant/world-sim/scenario-1/actor-1")));

        when(agentConversationContextRepository.findByContextIdAndUserId("ctx-codex-biz-1", "user-1"))
                .thenReturn(Optional.of(boundContext));
        when(sessionRepository.findById("session-codex-biz-bound")).thenReturn(Optional.of(session));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));
        assertTrue(error.getMessage().contains("CONTEXT_WORKER_MISMATCH"));
        assertTrue(error.getMessage().contains("privateAccountId"));
        verify(codexBizProvider, never()).resumeTask(anyString(), anyString(), any());
        verify(codexProvider, never()).resumeTask(anyString(), anyString(), any());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void createTask_withContextIdBoundToAnotherUserRejectsBeforeDispatch() {
        TypedTaskProvider codexProvider = mock(TypedTaskProvider.class);
        facade = createFacade(List.of(codexProvider));
        ReflectionTestUtils.setField(facade, "agentConversationContextRepository", agentConversationContextRepository);

        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .contextId("ctx-owned-by-other")
                .agentId("agent-1")
                .prompt("continue")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("OPEN_API")
                .build();

        AgentConversationContextEntity existing = new AgentConversationContextEntity();
        existing.setContextId("ctx-owned-by-other");
        existing.setUserId("user-2");
        existing.setTargetAgentId("agent-1");
        existing.setNavigatorSessionId("session-other");

        when(agentConversationContextRepository.findById("ctx-owned-by-other"))
                .thenReturn(Optional.of(existing));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> facade.createTask(request, context));
        assertTrue(error.getMessage().contains("CONTEXT_WORKER_MISMATCH"));
        verifyNoInteractions(codexProvider, agentResolver);
    }

    @Test
    void resumeTask_usesSessionAgentIdWhenLegacySessionHasNoProviderType() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-1")
                .prompt("continue")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-resumed")
                .providerType("claude-worker")
                .build();

        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setAgentId("agent-claude-1");
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

        when(agentResolver.getProviderType(eq("agent-claude-1"), any())).thenReturn(Optional.of("claude-worker"));
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        when(taskQueryProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-resumed", result.getTaskId());
        verify(agentResolver).getProviderType(eq("agent-claude-1"), any());
    }

    @Test
    void resumeTask_withoutBoundProviderOrExplicitContext_throwsException() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .prompt("continue")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .requestSource("UI")
                .build();

        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(new SessionEntity()));

        assertThrows(IllegalArgumentException.class, () -> facade.resumeTask(request, context));
        verifyNoInteractions(agentResolver);
    }

    @Test
    void listTasksByDirectory_prefersUnifiedStore() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        SessionTaskEntity task = sessionTask(
                "task-dir-1", "session-1", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 24, 21, 0), "{\"claudeSessionId\":\"cs-1\"}"
        );
        when(sessionTaskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc("dir-1", "user-1"))
                .thenReturn(List.of(task));

        List<DispatchTaskDTO> result = facade.listTasksByDirectory("user-1", "dir-1");

        assertEquals(1, result.size());
        assertEquals("task-dir-1", result.get(0).getTaskId());
        verify(taskQueryProvider, never()).listTasksByDirectory(anyString(), anyString());
    }

    @Test
    void deleteTask_removesUnifiedSessionStoreRecordSoHistoryPageNoLongerShowsDeletedConversation() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        SessionTaskEntity task = sessionTask(
                "task-delete-1", "session-delete-1", "codex-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 26, 10, 0), "{\"codexThreadId\":\"thread-delete-1\"}"
        );

        Map<String, SessionTaskEntity> store = new LinkedHashMap<>();
        store.put(task.getTaskId(), task);

        when(sessionTaskRepository.findByTaskId("task-delete-1"))
                .thenAnswer(invocation -> Optional.ofNullable(store.get("task-delete-1")));
        when(sessionTaskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc("dir-1", "user-1"))
                .thenAnswer(invocation -> store.values().stream()
                        .filter(entity -> "dir-1".equals(entity.getDirectoryId()) && "user-1".equals(entity.getUserId()))
                        .sorted(Comparator.comparing(SessionTaskEntity::getCreatedAt).reversed())
                        .toList());
        doAnswer(invocation -> {
            store.remove(invocation.getArgument(0));
            return null;
        }).when(sessionTaskRepository).deleteByTaskId(anyString());
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");

        Map<?, ?> beforeDelete = assertInstanceOf(Map.class, facade.listTasksByDirectoryPaged("user-1", "dir-1", 0, 20, null));
        assertEquals(1L, beforeDelete.get("totalSessions"));

        facade.deleteTask("task-delete-1", uiContext("user-1"));

        Map<?, ?> afterDelete = assertInstanceOf(Map.class, facade.listTasksByDirectoryPaged("user-1", "dir-1", 0, 20, null));
        assertEquals(0L, afterDelete.get("totalSessions"));
        assertEquals(List.of(), afterDelete.get("content"));
        verify(taskQueryProvider).deleteTask("user-1", "task-delete-1");
        verify(sessionTaskRepository).deleteByTaskId("task-delete-1");
    }

    @Test
    void toDispatchTaskDTO_preservesNullAgentIdInsteadOfFallingBackToProviderType() {
        // 设计规范 §5.1: 禁止用 providerType 常量覆盖真实 logicalAgentId
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(facade, "workingDirectoryRepository", workingDirectoryRepository);

        // 构造一个 agentId 为 null 的 SessionTaskEntity（模拟旧数据或未绑定 Agent 的任务）
        SessionTaskEntity taskWithNullAgent = sessionTask(
                "task-null-agent", "session-null-agent", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 27, 10, 0), "{\"claudeSessionId\":\"cs-1\"}"
        );
        // agentId 未设置，保持 null

        when(sessionTaskRepository.findByTaskIdAndUserId("task-null-agent", "user-1"))
                .thenReturn(Optional.of(taskWithNullAgent));
        when(workingDirectoryRepository.findByDirectoryIdIn(List.of("dir-1")))
                .thenReturn(List.of(directoryEntity("dir-1", "Test Project")));

        Optional<DispatchTaskDTO> result = facade.getTask("task-null-agent",
                AgentResolveContext.builder().userId("user-1").build());

        assertTrue(result.isPresent());
        DispatchTaskDTO dto = result.orElseThrow();
        // 关键断言：agentId 应为 null，不应回退到 "claude-worker"
        assertNull(dto.getAgentId(),
                "agentId should be null when entity has no logical agent, not fall back to providerType");
        assertEquals("claude-worker", dto.getProviderType());
    }

    @Test
    void toDispatchTaskDTO_readsSchemaVersionedTaskStateProviderFields() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(facade, "workingDirectoryRepository", workingDirectoryRepository);

        Map<String, Object> providerState = new LinkedHashMap<>();
        providerState.put(ProviderStateCodec.FIELD_CODEX_THREAD_ID, "thread-v1");
        providerState.put(ProviderStateCodec.FIELD_CONTEXT_ID, "ctx-v1");
        providerState.put(ProviderStateCodec.FIELD_CODEX_RUNTIME_ID, "app-server-main");
        providerState.put(ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION, "7");
        providerState.put(ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE, "APP_SERVER");
        providerState.put(ProviderStateCodec.FIELD_CODEX_RUNTIME_INSTANCE_ID, "instance-v1");
        providerState.put(ProviderStateCodec.FIELD_CODEX_ROUTING_EPOCH, 11);
        providerState.put(ProviderStateCodec.FIELD_RUNTIME_ACCEPTANCE_STATE, "TERMINAL");
        providerState.put(ProviderStateCodec.FIELD_CHECKPOINTS, List.of(Map.of("id", "ckpt-v1")));
        providerState.put("fileCheckpointingEnabled", true);
        String taskStateJson = ProviderStateCodec.mergeTaskValues(null, "codex-worker", providerState);
        SessionTaskEntity task = sessionTask(
                "task-schema-v1", "session-schema-v1", "codex-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 27, 10, 0), taskStateJson
        );

        when(sessionTaskRepository.findByTaskIdAndUserId("task-schema-v1", "user-1"))
                .thenReturn(Optional.of(task));
        when(workingDirectoryRepository.findByDirectoryIdIn(List.of("dir-1")))
                .thenReturn(List.of(directoryEntity("dir-1", "Codex Project")));

        Optional<DispatchTaskDTO> result = facade.getTask("task-schema-v1",
                AgentResolveContext.builder().userId("user-1").build());

        assertTrue(result.isPresent());
        DispatchTaskDTO dto = result.orElseThrow();
        assertEquals("codex-worker", dto.getProviderType());
        assertEquals("thread-v1", dto.getCodexThreadId());
        assertEquals("ctx-v1", dto.getContextId());
        assertEquals("app-server-main", dto.getRuntimeId());
        assertEquals(7, dto.getRuntimeRevision());
        assertEquals("APP_SERVER", dto.getRuntimeType());
        assertEquals("instance-v1", dto.getRuntimeInstanceId());
        assertEquals(11L, dto.getRoutingEpoch());
        assertEquals("TERMINAL", dto.getRuntimeAcceptanceState());
        assertEquals(Boolean.TRUE, dto.getFileCheckpointingEnabled());
        assertNotNull(dto.getCheckpoints());
        assertTrue(dto.getCheckpoints().contains("ckpt-v1"));
        assertEquals("Codex Project", dto.getDirectoryName());
    }

    @Test
    void toDispatchTaskDTO_preservesRealLogicalAgentId() {
        // 设计规范 §11.4: session.agentId 必须保存真实逻辑 Agent，而不是 provider 常量
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);

        SessionTaskEntity taskWithAgent = sessionTask(
                "task-real-agent", "session-real-agent", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 27, 10, 0), "{\"claudeSessionId\":\"cs-1\"}"
        );
        taskWithAgent.setAgentId("agent-claude-prod-1");

        when(sessionTaskRepository.findByTaskIdAndUserId("task-real-agent", "user-1"))
                .thenReturn(Optional.of(taskWithAgent));

        Optional<DispatchTaskDTO> result = facade.getTask("task-real-agent",
                AgentResolveContext.builder().userId("user-1").build());

        assertTrue(result.isPresent());
        DispatchTaskDTO dto = result.orElseThrow();
        assertEquals("agent-claude-prod-1", dto.getAgentId(),
                "agentId must be the real logical agent, not the provider constant");
        assertEquals("claude-worker", dto.getProviderType());
    }

    @Test
    void deleteTask_cleansUnifiedSessionStoreWhenProviderTaskIsAlreadyMissing() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(facade, "nativeSubtaskStateRepository", nativeSubtaskStateRepository);

        SessionTaskEntity staleTask = sessionTask(
                "task-stale-1", "session-stale-1", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 3, 26, 11, 0), "{\"claudeSessionId\":\"session-worker-1\"}"
        );

        Map<String, SessionTaskEntity> store = new LinkedHashMap<>();
        store.put(staleTask.getTaskId(), staleTask);

        when(sessionTaskRepository.findByTaskId("task-stale-1"))
                .thenAnswer(invocation -> Optional.ofNullable(store.get("task-stale-1")));
        when(sessionTaskRepository.findByTaskIdAndUserId("task-stale-1", "user-1"))
                .thenReturn(Optional.of(staleTask));
        when(sessionTaskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc("dir-1", "user-1"))
                .thenAnswer(invocation -> store.values().stream()
                        .filter(entity -> "dir-1".equals(entity.getDirectoryId()) && "user-1".equals(entity.getUserId()))
                        .sorted(Comparator.comparing(SessionTaskEntity::getCreatedAt).reversed())
                        .toList());
        doAnswer(invocation -> {
            store.remove(invocation.getArgument(0));
            return null;
        }).when(sessionTaskRepository).deleteByTaskId(anyString());
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        doThrow(new IllegalArgumentException("Task not found: task-stale-1"))
                .when(taskQueryProvider).deleteTask("user-1", "task-stale-1");

        Map<?, ?> beforeDelete = assertInstanceOf(Map.class, facade.listTasksByDirectoryPaged("user-1", "dir-1", 0, 20, null));
        assertEquals(1L, beforeDelete.get("totalSessions"));

        assertDoesNotThrow(() -> facade.deleteTask("task-stale-1", uiContext("user-1")));

        Map<?, ?> afterDelete = assertInstanceOf(Map.class, facade.listTasksByDirectoryPaged("user-1", "dir-1", 0, 20, null));
        assertEquals(0L, afterDelete.get("totalSessions"));
        assertEquals(List.of(), afterDelete.get("content"));
        verify(taskQueryProvider).deleteTask("user-1", "task-stale-1");
        verify(sessionTaskRepository).deleteByTaskId("task-stale-1");
        verify(nativeSubtaskStateRepository).deleteByTaskId("task-stale-1");
    }

    @Test
    void deleteTask_keepsUnifiedProjectionAsRetryMarkerWhenNativeCleanupFails() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(facade, "nativeSubtaskStateRepository", nativeSubtaskStateRepository);
        SessionTaskEntity staleTask = sessionTask(
                "task-stale-retry", "session-stale-retry", "claude-worker", "worker-1", "dir-1",
                "COMPLETED", LocalDateTime.of(2026, 7, 10, 12, 30), null
        );
        when(sessionTaskRepository.findByTaskId("task-stale-retry")).thenReturn(Optional.of(staleTask));
        when(sessionTaskRepository.findByTaskIdAndUserId("task-stale-retry", "user-1"))
                .thenReturn(Optional.of(staleTask));
        when(taskQueryProvider.getProviderType()).thenReturn("claude-worker");
        doThrow(new IllegalArgumentException("Task not found: task-stale-retry"))
                .when(taskQueryProvider).deleteTask("user-1", "task-stale-retry");
        doThrow(new IllegalStateException("native store unavailable"))
                .when(nativeSubtaskStateRepository).deleteByTaskId("task-stale-retry");

        assertThrows(IllegalStateException.class,
                () -> facade.deleteTask("task-stale-retry", uiContext("user-1")));

        verify(sessionTaskRepository, never()).deleteByTaskId("task-stale-retry");
    }

    @Test
    void deleteTask_doesNotCleanAnotherUsersProjectionWhenProviderReportsMissing() {
        ReflectionTestUtils.setField(facade, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(facade, "nativeSubtaskStateRepository", nativeSubtaskStateRepository);
        doThrow(new SecurityException("resource is not accessible"))
                .when(resourceAccessService)
                .requireOwnedTask("task-owned-by-other", "attacker", "tenant-1");

        assertThrows(SecurityException.class,
                () -> facade.deleteTask("task-owned-by-other", uiContext("attacker")));

        verify(sessionTaskRepository, never()).deleteByTaskId("task-owned-by-other");
        verify(nativeSubtaskStateRepository, never()).deleteByTaskId("task-owned-by-other");
    }

    private AgentResolveContext uiContext(String userId) {
        return AgentResolveContext.builder()
                .userId(userId)
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();
    }

    private interface TypedTaskProvider extends TaskLookupProvider,
            TaskCommandProvider,
            TaskListingProvider,
            WorkerSessionQueryProvider {
    }

    private static final class WorkerSessionOnlyProvider implements WorkerSessionQueryProvider {

        @Override
        public String getProviderType() {
            return "worker-session-only";
        }

        @Override
        public Set<TaskQueryCapability> getCapabilities() {
            return Set.of(TaskQueryCapability.LIST_WORKER_SESSIONS);
        }

        @Override
        public List<WorkerSessionSummary> listWorkerSessionSummaries(String workerId, String userId) {
            return WorkerSessionSummary.fromList(List.of(Map.of("sessionId", "worker-only-session")));
        }
    }

    private static final class TypedWorkerSessionProvider implements WorkerSessionQueryProvider {

        @Override
        public String getProviderType() {
            return "typed-worker-session";
        }

        @Override
        public Set<TaskQueryCapability> getCapabilities() {
            return Set.of(
                    TaskQueryCapability.LIST_WORKER_SESSIONS,
                    TaskQueryCapability.GET_WORKER_SESSION_MESSAGE_COUNT,
                    TaskQueryCapability.GET_WORKER_SESSION_MESSAGES,
                    TaskQueryCapability.SYNC_WORKER_SESSIONS);
        }

        @Override
        public List<WorkerSessionSummary> listWorkerSessionSummaries(String workerId, String userId) {
            return WorkerSessionSummary.fromList(
                    List.of(Map.of("session_id", "legacy-session", "status", "RUNNING")));
        }

        @Override
        public WorkerSessionMessageCount getWorkerSessionMessageCountResult(
                String workerId, String sessionId, String userId) {
            return WorkerSessionMessageCount.from(
                    Map.of("user_count", 1, "assistant_count", 1, "total", 2));
        }

        @Override
        public List<WorkerSessionMessage> listWorkerSessionMessages(
                String workerId, String sessionId, String userId, Integer offset, Integer limit) {
            return WorkerSessionMessage.fromList(
                    List.of(Map.of("role", "assistant", "content", "ok")));
        }

        @Override
        public WorkerSessionSyncResult syncWorkerSessionState(String workerId, String userId, String tenantId) {
            return WorkerSessionSyncResult.from(Map.of("synced", 0, "total", 1));
        }
    }
}
