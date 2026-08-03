package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.util.DirectoryAgentId;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SessionCodingAgentRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskCreateTargetResolverTest {

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private SessionTaskResourceAccessService resourceAccessService;
    @Mock
    private WorkingDirectoryRepository workingDirectoryRepository;
    @Mock
    private SessionCodingAgentRepository codingAgentRepository;
    @Mock
    private TaskCommandProvider commandProvider;
    @Mock
    private LlmModelManager llmModelManager;
    @Mock
    private WorkerManagementFacade workerManagementFacade;
    @Mock
    private UnifiedAgentResolver agentResolver;

    private TaskCreateTargetResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = resolver(workerManagementFacade, workingDirectoryRepository, codingAgentRepository);
    }

    @Test
    void ownedSessionRejectsWorkerDriftBeforeAnyWorkerOrProviderLookup() {
        SessionEntity session = ownedSession("session-1", "user-1", "tenant-1");
        session.setCurrentWorkerId("worker-owned");
        when(resourceAccessService.requireOwnedSession("session-1", "user-1", "tenant-1"))
                .thenReturn(session);
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .workerId("worker-attacker")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveCreateExecutionPlan(request, context("user-1", "tenant-1")));

        assertTrue(error.getMessage().contains("workerId"));
        verifyNoInteractions(workerManagementFacade, agentResolver, commandProvider);
    }

    @Test
    void ownedDirectoryResolvesDirectPlanWithoutInventingLogicalAgent() {
        WorkingDirectoryEntity directory = ownedDirectory(
                "dir-1", "user-1", "tenant-1", "worker-1", "cfg-codex", true);
        when(workingDirectoryRepository.findByDirectoryIdAndUserId("dir-1", "user-1"))
                .thenReturn(Optional.of(directory));
        when(llmModelManager.getModelConfig("cfg-codex"))
                .thenReturn(Optional.of(modelConfig("cfg-codex", "OPENAI_CODEX", null)));
        when(commandProvider.getProviderType()).thenReturn("codex-worker");
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId(DirectoryAgentId.of("dir-1"))
                .build();

        TaskCreateTargetResolver.CreateExecutionPlan plan =
                resolver.resolveCreateExecutionPlan(request, context("user-1", "tenant-1"));

        assertEquals(TaskCreateTargetResolver.ExecutionRoute.DIRECT, plan.executionRoute());
        assertNull(plan.logicalAgentId());
        assertEquals("codex-worker", plan.providerType());
        assertEquals("worker-1", plan.physicalWorkerId());
        assertEquals("cfg-codex", plan.modelConfigId());
        assertNull(plan.model());
        assertEquals("dir-1", plan.directoryId());
        assertEquals(DirectoryAgentId.of("dir-1"), request.getAgentId(),
                "pure resolution must retain the caller's selection constraint");
        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(llmModelManager).validateModelAccessForWorker("cfg-codex", "worker-1");
        verifyNoInteractions(agentResolver);
    }

    @Test
    void ownedCodingAgentResolvesExactLocalA2aPlan() {
        CodingAgentEntity agent = ownedAgent(
                "agent-1", "user-1", "tenant-1", "LOCAL_CLAUDE_WORKER", "worker-1", true);
        agent.setDefaultDirectoryId("dir-agent-default");
        when(codingAgentRepository.findByAgentIdAndUserId("agent-1", "user-1"))
                .thenReturn(Optional.of(agent));
        when(workingDirectoryRepository.findByDirectoryIdAndUserId("dir-agent-default", "user-1"))
                .thenReturn(Optional.of(ownedDirectory(
                        "dir-agent-default", "user-1", "tenant-1",
                        "worker-1", null, true)));
        TaskDispatchRequest request = TaskDispatchRequest.builder().agentId("agent-1").build();

        TaskCreateTargetResolver.CreateExecutionPlan plan =
                resolver.resolveCreateExecutionPlan(request, context("user-1", "tenant-1"));

        assertEquals(TaskCreateTargetResolver.ExecutionRoute.A2A, plan.executionRoute());
        assertEquals("agent-1", plan.logicalAgentId());
        assertEquals("claude-worker", plan.providerType());
        assertEquals("worker-1", plan.physicalWorkerId());
        assertEquals("dir-agent-default", plan.directoryId());
        assertEquals("agent-1", plan.agentLookup().lookupId());

        CodingAgentEntity langBizAgent = ownedAgent(
                "lang-agent-1", "user-1", "tenant-1",
                "LOCAL_LANGGRAPH_WORKER", "lang-worker-1", true);
        when(codingAgentRepository.findByAgentIdAndUserId("lang-agent-1", "user-1"))
                .thenReturn(Optional.of(langBizAgent));
        TaskCreateTargetResolver.CreateExecutionPlan langBizPlan =
                resolver.resolveCreateExecutionPlan(
                        TaskDispatchRequest.builder().agentId("lang-agent-1").build(),
                        context("user-1", "tenant-1"));
        assertEquals(TaskCreateTargetResolver.ExecutionRoute.A2A, langBizPlan.executionRoute());
        assertEquals("langgraph-biz-worker", langBizPlan.providerType());
        assertEquals("lang-worker-1", langBizPlan.physicalWorkerId());
        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "lang-worker-1");
        verifyNoInteractions(agentResolver);
    }

    @Test
    void bareLocalWorkerUsesProviderNeutralAccessProof() {
        when(commandProvider.getProviderType()).thenReturn("codex-worker");
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .providerType("codex-worker")
                .workerId("worker-1")
                .build();

        TaskCreateTargetResolver.CreateExecutionPlan plan =
                resolver.resolveCreateExecutionPlan(request, context("user-1", "tenant-1"));

        assertEquals(TaskCreateTargetResolver.ExecutionRoute.DIRECT, plan.executionRoute());
        assertNull(plan.logicalAgentId());
        assertEquals("worker-1", plan.physicalWorkerId());
        assertNull(plan.sessionId());
        assertNull(plan.modelConfigId());
        assertNull(plan.model());
        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "worker-1");
    }

    @Test
    void externalA2aAllowsCanonicalWorkerSessionAndModelAbsence() {
        CodingAgentEntity agent = ownedAgent(
                "external-1", "user-1", "tenant-1", "EXTERNAL_A2A", null, true);
        when(codingAgentRepository.findByAgentIdAndUserId("external-1", "user-1"))
                .thenReturn(Optional.of(agent));
        when(agentResolver.listByProviderType(eq("partner-a2a"), any(AgentResolveContext.class)))
                .thenReturn(List.of(A2aAgentCard.builder().id("external-1").build()));
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .agentId("external-1")
                .providerType("partner-a2a")
                .build();

        TaskCreateTargetResolver.CreateExecutionPlan plan =
                resolver.resolveCreateExecutionPlan(request, context("user-1", "tenant-1"));

        assertEquals(TaskCreateTargetResolver.ExecutionRoute.A2A, plan.executionRoute());
        assertEquals("partner-a2a", plan.providerType());
        assertEquals("external-1", plan.logicalAgentId());
        assertNull(plan.physicalWorkerId());
        assertNull(plan.sessionId());
        assertNull(plan.directoryId());
        assertNull(plan.modelConfigId());
        assertNull(plan.model());
    }

    @Test
    void crossTenantAgentIsRejectedBeforeProviderEffectResolution() {
        CodingAgentEntity wrongTenant = ownedAgent(
                "agent-1", "user-1", "tenant-other", "LOCAL_CLAUDE_WORKER", "worker-1", true);
        when(codingAgentRepository.findByAgentIdAndUserId("agent-1", "user-1"))
                .thenReturn(Optional.of(wrongTenant));

        assertThrows(SecurityException.class, () -> resolver.resolveCreateExecutionPlan(
                TaskDispatchRequest.builder().agentId("agent-1").build(),
                context("user-1", "tenant-1")));

        verifyNoInteractions(agentResolver, workerManagementFacade);
    }

    @Test
    void disabledDirectoryIsRejectedFailClosed() {
        WorkingDirectoryEntity disabled = ownedDirectory(
                "dir-1", "user-1", "tenant-1", "worker-1", "cfg-codex", false);
        when(workingDirectoryRepository.findByDirectoryIdAndUserId("dir-1", "user-1"))
                .thenReturn(Optional.of(disabled));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> resolver.resolveCreateExecutionPlan(
                        TaskDispatchRequest.builder().directoryId("dir-1").providerType("codex-worker").build(),
                        context("user-1", "tenant-1")));

        assertTrue(error.getMessage().contains("disabled"));
        disabled.setEnabled(null);
        IllegalStateException unknownEnabled = assertThrows(IllegalStateException.class,
                () -> resolver.resolveCreateExecutionPlan(
                        TaskDispatchRequest.builder().directoryId("dir-1").providerType("codex-worker").build(),
                        context("user-1", "tenant-1")));
        assertTrue(unknownEnabled.getMessage().contains("disabled"));
        verifyNoInteractions(commandProvider, workerManagementFacade, agentResolver);
    }

    @Test
    void modelOwnershipAndAgentProviderConflictsAreRejectedBeforeRuntimeAgentLookup() {
        CodingAgentEntity agent = ownedAgent(
                "agent-1", "user-1", "tenant-1", "LOCAL_CLAUDE_WORKER", "worker-1", true);
        when(codingAgentRepository.findByAgentIdAndUserId("agent-1", "user-1"))
                .thenReturn(Optional.of(agent));
        LlmModelConfigDTO config = modelConfig("cfg-codex", "OPENAI_CODEX", "gpt-5.4");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(config));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveCreateExecutionPlan(
                        TaskDispatchRequest.builder()
                                .agentId("agent-1")
                                .modelConfigId("cfg-codex")
                                .build(),
                        context("user-1", "tenant-1")));

        assertTrue(error.getMessage().contains("claude-worker"));
        assertTrue(error.getMessage().contains("codex-worker"));

        TaskDispatchRequest modelOnly = TaskDispatchRequest.builder()
                .modelConfigId("cfg-codex")
                .build();
        config.setTenantId("tenant-other");
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveCreateExecutionPlan(modelOnly, context("user-1", "tenant-1")));
        config.setTenantId("tenant-1");
        config.setOwnerId(null);
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveCreateExecutionPlan(modelOnly, context("user-1", "tenant-1")));
        config.setOwnerId("platform");
        config.setEnabled(null);
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveCreateExecutionPlan(modelOnly, context("user-1", "tenant-1")));
        config.setEnabled(true);
        config.setTenantId(null);
        when(commandProvider.getProviderType()).thenReturn("codex-worker");
        TaskCreateTargetResolver.CreateExecutionPlan personalPlan =
                resolver.resolveCreateExecutionPlan(modelOnly, context("user-1", null));
        assertNull(personalPlan.tenantId());
        assertEquals("codex-worker", personalPlan.providerType());
        assertNull(personalPlan.physicalWorkerId());
        verifyNoInteractions(agentResolver);
    }

    @Test
    void missingBareWorkerAuthorityIsRejectedFailClosed() {
        resolver = resolver(null, workingDirectoryRepository, codingAgentRepository);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> resolver.resolveCreateExecutionPlan(
                        TaskDispatchRequest.builder()
                                .providerType("codex-worker")
                                .workerId("worker-1")
                                .build(),
                        context("user-1", "tenant-1")));

        assertTrue(error.getMessage().contains("fail-closed"));
        verify(commandProvider, never()).createTaskDirect(any(), any(), any());
    }

    private TaskCreateTargetResolver resolver(WorkerManagementFacade workers,
                                               WorkingDirectoryRepository directories,
                                               SessionCodingAgentRepository agents) {
        return new TaskCreateTargetResolver(
                sessionRepository,
                resourceAccessService,
                directories,
                agents,
                new TaskQueryProviderRegistry(List.of(), List.of(commandProvider), List.of(), List.of()),
                llmModelManager,
                workers,
                agentResolver);
    }

    private AgentResolveContext context(String userId, String tenantId) {
        return AgentResolveContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .requestSource("UI")
                .build();
    }

    private SessionEntity ownedSession(String sessionId, String userId, String tenantId) {
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        return entity;
    }

    private WorkingDirectoryEntity ownedDirectory(String directoryId,
                                                   String userId,
                                                   String tenantId,
                                                   String workerId,
                                                   String modelConfigId,
                                                   boolean enabled) {
        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(directoryId);
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setWorkerId(workerId);
        entity.setDefaultModelConfigId(modelConfigId);
        entity.setEnabled(enabled);
        return entity;
    }

    private CodingAgentEntity ownedAgent(String agentId,
                                         String userId,
                                         String tenantId,
                                         String agentType,
                                         String workerId,
                                         boolean enabled) {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId(agentId);
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setAgentType(agentType);
        entity.setWorkerId(workerId);
        entity.setEnabled(enabled);
        return entity;
    }

    private LlmModelConfigDTO modelConfig(String id, String backend, String model) {
        LlmModelConfigDTO dto = new LlmModelConfigDTO();
        dto.setId(id);
        dto.setWorkerBackend(backend);
        dto.setModelName(model);
        dto.setTenantId("tenant-1");
        dto.setOwnerType(ResourceOwnerType.PLATFORM);
        dto.setOwnerId("platform");
        dto.setEnabled(true);
        return dto;
    }
}
