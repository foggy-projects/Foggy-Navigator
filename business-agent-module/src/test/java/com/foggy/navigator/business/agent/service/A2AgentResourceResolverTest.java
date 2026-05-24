package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.service.A2AgentResourceResolver.ResolvedModelResource;
import com.foggy.navigator.business.agent.service.A2AgentResourceResolver.ResolvedWorkspaceResource;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.repository.BusinessAgentDirectoryBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentModelBindingRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.business.agent.service.worker.PhysicalWorkerRuntimeRegistry;
import com.foggy.navigator.business.agent.service.worker.ResolvedPhysicalWorker;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import com.foggy.navigator.common.entity.AgentModelBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2AgentResourceResolverTest {

    private ClientAppModelConfigGrantService modelConfigGrantService;
    private LlmModelManager llmModelManager;
    private ClientAppService clientAppService;
    private WorkingDirectoryRepository workingDirectoryRepository;
    private BusinessCodingAgentRepository agentRepository;
    private BizWorkerPoolRepository workerPoolRepository;
    private PhysicalWorkerRuntimeRegistry physicalWorkerRuntimeRegistry;
    private BusinessAgentDirectoryBindingRepository agentDirectoryBindingRepository;
    private BusinessAgentModelBindingRepository agentModelBindingRepository;
    private A2AgentResourceResolver resolver;

    @BeforeEach
    void setUp() {
        modelConfigGrantService = mock(ClientAppModelConfigGrantService.class);
        llmModelManager = mock(LlmModelManager.class);
        clientAppService = mock(ClientAppService.class);
        workingDirectoryRepository = mock(WorkingDirectoryRepository.class);
        agentRepository = mock(BusinessCodingAgentRepository.class);
        workerPoolRepository = mock(BizWorkerPoolRepository.class);
        physicalWorkerRuntimeRegistry = mock(PhysicalWorkerRuntimeRegistry.class);
        agentDirectoryBindingRepository = mock(BusinessAgentDirectoryBindingRepository.class);
        agentModelBindingRepository = mock(BusinessAgentModelBindingRepository.class);
        resolver = new A2AgentResourceResolver(
                modelConfigGrantService,
                llmModelManager,
                clientAppService,
                workingDirectoryRepository,
                agentRepository,
                workerPoolRepository,
                List.of(physicalWorkerRuntimeRegistry),
                agentDirectoryBindingRepository,
                agentModelBindingRepository);
        when(llmModelManager.getModelConfig(anyString())).thenAnswer(invocation ->
                Optional.of(model(invocation.getArgument(0, String.class))));
    }

    @Test
    void resolveRequiredModel_reports_requested_model_grant_source() {
        when(modelConfigGrantService.resolveEffectiveModelConfigId(
                "tenant-1", "capp-1", "cfg-requested", LlmModelCategory.GENERAL))
                .thenReturn("cfg-requested");

        ResolvedModelResource result = resolver.resolveRequiredModel(
                "tenant-1", "capp-1", " cfg-requested ", LlmModelCategory.GENERAL);

        assertEquals("cfg-requested", result.modelConfigId());
        assertEquals("cfg-requested", result.requestedModelConfigId());
        assertNull(result.requestedModelVariant());
        assertEquals(LlmModelCategory.GENERAL, result.category());
        assertEquals("cfg-requested-name", result.modelName());
        assertEquals("MODEL_CONFIG_DEFAULT", result.modelNameSource());
        assertEquals("LANGGRAPH_BIZ", result.workerBackend());
        assertEquals("REQUESTED_MODEL_GRANT", result.source());
    }

    @Test
    void resolveRequiredModel_reports_default_model_grant_source() {
        when(modelConfigGrantService.resolveEffectiveModelConfigId(
                "tenant-1", "capp-1", null, LlmModelCategory.GENERAL))
                .thenReturn("cfg-default");

        ResolvedModelResource result = resolver.resolveRequiredModel(
                "tenant-1", "capp-1", " ", LlmModelCategory.GENERAL);

        assertEquals("cfg-default", result.modelConfigId());
        assertNull(result.requestedModelConfigId());
        assertEquals("cfg-default-name", result.modelName());
        assertEquals("MODEL_CONFIG_DEFAULT", result.modelNameSource());
        assertEquals("DEFAULT_MODEL_GRANT", result.source());
    }

    @Test
    void resolveRequiredModel_uses_requested_model_variant_when_allowed() {
        when(modelConfigGrantService.resolveEffectiveModelConfigId(
                "tenant-1", "capp-1", "cfg-1", LlmModelCategory.GENERAL))
                .thenReturn("cfg-1");
        when(llmModelManager.getModelConfig("cfg-1"))
                .thenReturn(Optional.of(model("cfg-1", List.of("sonnet", "opus"))));

        ResolvedModelResource result = resolver.resolveRequiredModel(
                "tenant-1", "capp-1", "cfg-1", " opus ", null, LlmModelCategory.GENERAL);

        assertEquals("opus", result.modelName());
        assertEquals("opus", result.requestedModelVariant());
        assertEquals("REQUESTED_MODEL_VARIANT", result.modelNameSource());
    }

    @Test
    void resolveRequiredModel_rejects_requested_model_variant_outside_available_models() {
        when(modelConfigGrantService.resolveEffectiveModelConfigId(
                "tenant-1", "capp-1", "cfg-1", LlmModelCategory.GENERAL))
                .thenReturn("cfg-1");
        when(llmModelManager.getModelConfig("cfg-1"))
                .thenReturn(Optional.of(model("cfg-1", List.of("sonnet", "opus"))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                resolver.resolveRequiredModel(
                        "tenant-1", "capp-1", "cfg-1", "haiku", null, LlmModelCategory.GENERAL));

        assertTrue(error.getMessage().contains("modelVariant is not allowed"));
    }

    @Test
    void resolveOptionalModel_returns_empty_without_default_grant() {
        when(modelConfigGrantService.tryResolveEffectiveModelConfigId(
                "tenant-1", "capp-1", null, LlmModelCategory.VISION))
                .thenReturn(Optional.empty());

        Optional<ResolvedModelResource> result = resolver.resolveOptionalModel(
                "tenant-1", "capp-1", LlmModelCategory.VISION);

        assertTrue(result.isEmpty());
        verify(modelConfigGrantService).tryResolveEffectiveModelConfigId(
                "tenant-1", "capp-1", null, LlmModelCategory.VISION);
    }

    @Test
    void resolveRequiredModelForAgent_allows_agent_default_model_without_binding() {
        when(modelConfigGrantService.resolveEffectiveModelConfigId(
                "tenant-1", "capp-1", "model-1", LlmModelCategory.GENERAL))
                .thenReturn("model-1");

        ResolvedModelResource result = resolver.resolveRequiredModelForAgent(
                "tenant-1",
                "capp-1",
                agentResource("agent-1", "model-1", "dir-default"),
                "model-1",
                LlmModelCategory.GENERAL);

        assertEquals("model-1", result.modelConfigId());
        assertEquals("AGENT_DEFAULT_MODEL:REQUESTED_MODEL_GRANT", result.source());
    }

    @Test
    void resolveRequiredModelForAgent_allows_bound_non_default_model() {
        when(modelConfigGrantService.resolveEffectiveModelConfigId(
                "tenant-1", "capp-1", "model-2", LlmModelCategory.GENERAL))
                .thenReturn("model-2");
        AgentModelBindingEntity binding = new AgentModelBindingEntity();
        binding.setTenantId("tenant-1");
        binding.setAgentId("agent-1");
        binding.setModelConfigId("model-2");
        when(agentModelBindingRepository.findByTenantIdAndAgentIdAndModelConfigId(
                "tenant-1", "agent-1", "model-2"))
                .thenReturn(Optional.of(binding));

        ResolvedModelResource result = resolver.resolveRequiredModelForAgent(
                "tenant-1",
                "capp-1",
                agentResource("agent-1", "model-1", "dir-default"),
                "model-2",
                LlmModelCategory.GENERAL);

        assertEquals("model-2", result.modelConfigId());
        assertEquals("AGENT_MODEL_BINDING:REQUESTED_MODEL_GRANT", result.source());
    }

    @Test
    void resolveRequiredModelForAgent_rejects_unbound_non_default_model() {
        when(modelConfigGrantService.resolveEffectiveModelConfigId(
                "tenant-1", "capp-1", "model-2", LlmModelCategory.GENERAL))
                .thenReturn("model-2");
        when(agentModelBindingRepository.findByTenantIdAndAgentIdAndModelConfigId(
                "tenant-1", "agent-1", "model-2"))
                .thenReturn(Optional.empty());

        SecurityException error = assertThrows(SecurityException.class, () ->
                resolver.resolveRequiredModelForAgent(
                        "tenant-1",
                        "capp-1",
                        agentResource("agent-1", "model-1", "dir-default"),
                        "model-2",
                        LlmModelCategory.GENERAL));

        assertTrue(error.getMessage().contains("not bound to agent"));
    }

    @Test
    void resolveOptionalModelForAgent_returns_empty_when_optional_model_unbound() {
        when(modelConfigGrantService.tryResolveEffectiveModelConfigId(
                "tenant-1", "capp-1", null, LlmModelCategory.VISION))
                .thenReturn(Optional.of("vision-1"));
        when(agentModelBindingRepository.findByTenantIdAndAgentIdAndModelConfigId(
                "tenant-1", "agent-1", "vision-1"))
                .thenReturn(Optional.empty());

        Optional<ResolvedModelResource> result = resolver.resolveOptionalModelForAgent(
                "tenant-1",
                "capp-1",
                agentResource("agent-1", "model-1", "dir-default"),
                LlmModelCategory.VISION);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolveOptionalWorkspace_returns_empty_without_directory_id() {
        Optional<ResolvedWorkspaceResource> result = resolver.resolveOptionalWorkspace(
                "tenant-1", "capp-1", "user-1", " ");

        assertTrue(result.isEmpty());
    }

    @Test
    void resolveRequiredAgent_allows_client_app_owned_agent() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        CodingAgentEntity agent = agent("agent-1", ResourceOwnerType.CLIENT_APP, "capp-1");
        agent.setClientAppId("capp-1");
        agent.setWorkerId("pool-1");
        agent.setDefaultModelConfigId("model-1");
        agent.setDefaultDirectoryId("dir-1");
        agent.setAgentProfile("{\"skillId\":\"skill-1\"}");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.of(agent));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool("pool-1", ResourceOwnerType.PLATFORM, "tenant-1")));

        A2AgentResourceResolver.ResolvedAgentResource result = resolver.resolveRequiredAgent(
                "tenant-1", "capp-1", "user-1", "agent-1");

        assertEquals("agent-1", result.agentId());
        assertEquals(ResourceOwnerType.CLIENT_APP, result.ownerType());
        assertEquals("skill-1", result.skillId());
        assertEquals("pool-1", result.workerPoolId());
        assertEquals(ResourceOwnerType.PLATFORM, result.workerPoolOwnerType());
        assertEquals("WORKER_POOL:PLATFORM", result.workerPoolSource());
        assertEquals("LANGGRAPH_BIZ", result.workerBackend());
        assertEquals("model-1", result.defaultModelConfigId());
        assertEquals("dir-1", result.defaultDirectoryId());
        assertEquals("AGENT:CLIENT_APP", result.source());
    }

    @Test
    void resolveRequiredAgent_allows_physical_worker_ref() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        CodingAgentEntity agent = agent("agent-1", ResourceOwnerType.UPSTREAM_SYSTEM, "system-1");
        agent.setWorkerId("worker-1");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.of(agent));
        when(workerPoolRepository.findByPoolIdAndTenantId("worker-1", "tenant-1"))
                .thenReturn(Optional.empty());
        when(physicalWorkerRuntimeRegistry.resolve("tenant-1", "system-1", "worker-1"))
                .thenReturn(Optional.of(new ResolvedPhysicalWorker(
                        "worker-1",
                        "LANGGRAPH_BIZ",
                        ResourceOwnerType.UPSTREAM_SYSTEM,
                        "system-1",
                        "PHYSICAL_WORKER_IDENTITY:UPSTREAM_SYSTEM")));

        A2AgentResourceResolver.ResolvedAgentResource result = resolver.resolveRequiredAgent(
                "tenant-1", "capp-1", "user-1", "agent-1");

        assertNull(result.workerPoolId());
        assertEquals("LANGGRAPH_BIZ", result.workerBackend());
        assertEquals("worker-1", result.physicalWorkerId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.physicalWorkerOwnerType());
        assertEquals("PHYSICAL_WORKER_IDENTITY:UPSTREAM_SYSTEM", result.physicalWorkerSource());
    }

    @Test
    void resolveRequiredAgent_rejects_other_client_app_owned_agent() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        CodingAgentEntity agent = agent("agent-1", ResourceOwnerType.CLIENT_APP, "capp-2");
        agent.setClientAppId("capp-2");
        agent.setWorkerId("pool-1");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.of(agent));

        assertThrows(SecurityException.class, () ->
                resolver.resolveRequiredAgent("tenant-1", "capp-1", "user-1", "agent-1"));
    }

    @Test
    void resolveRequiredAgent_allows_upstream_system_owned_agent() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        CodingAgentEntity agent = agent("agent-1", ResourceOwnerType.UPSTREAM_SYSTEM, "system-1");
        agent.setWorkerId("pool-1");
        agent.setSkills("[{\"id\":\"skill-from-skills\"}]");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.of(agent));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool("pool-1", ResourceOwnerType.UPSTREAM_SYSTEM, "system-1")));

        A2AgentResourceResolver.ResolvedAgentResource result = resolver.resolveRequiredAgent(
                "tenant-1", "capp-1", "user-1", "agent-1");

        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.ownerType());
        assertEquals("skill-from-skills", result.skillId());
        assertEquals("pool-1", result.workerPoolId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.workerPoolOwnerType());
        assertEquals("system-1", result.workerPoolOwnerId());
    }

    @Test
    void resolveRequiredAgent_rejects_worker_pool_owned_by_other_upstream_system() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        CodingAgentEntity agent = agent("agent-1", ResourceOwnerType.CLIENT_APP, "capp-1");
        agent.setClientAppId("capp-1");
        agent.setWorkerId("pool-1");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.of(agent));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool("pool-1", ResourceOwnerType.UPSTREAM_SYSTEM, "system-2")));

        assertThrows(SecurityException.class, () ->
                resolver.resolveRequiredAgent("tenant-1", "capp-1", "user-1", "agent-1"));
    }

    @Test
    void resolveRequiredAgent_rejects_disabled_agent() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        CodingAgentEntity agent = agent("agent-1", ResourceOwnerType.CLIENT_APP, "capp-1");
        agent.setClientAppId("capp-1");
        agent.setEnabled(false);
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.of(agent));

        assertThrows(IllegalStateException.class, () ->
                resolver.resolveRequiredAgent("tenant-1", "capp-1", "user-1", "agent-1"));
    }

    @Test
    void resolveRequiredWorkspace_allows_user_private_directory_for_same_upstream_user() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        WorkingDirectoryEntity directory = directory("dir-1", WorkspaceScope.USER_PRIVATE, ResourceOwnerType.UPSTREAM_USER);
        directory.setClientAppId("capp-1");
        directory.setUpstreamUserId("user-1");
        directory.setOwnerId("capp-1:user-1");
        directory.setPath("D:/workspace/user-1");
        directory.setAllowedPathPrefixesJson("[\"D:/workspace\"]");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(workingDirectoryRepository.findByDirectoryId("dir-1")).thenReturn(Optional.of(directory));

        ResolvedWorkspaceResource result = resolver.resolveRequiredWorkspace(
                "tenant-1", "capp-1", "user-1", "dir-1");

        assertEquals("dir-1", result.directoryId());
        assertEquals(WorkspaceScope.USER_PRIVATE, result.workspaceScope());
        assertEquals(WorkingDirectoryResolverType.DELEGATED, result.resolverType());
        assertEquals("D:/workspace/user-1", result.workdir());
        assertEquals(List.of("D:/workspace"), result.allowedDirs());
        assertEquals("worker-1", result.physicalWorkerId());
        assertEquals("WORKING_DIRECTORY:USER_PRIVATE", result.source());
    }

    @Test
    void resolveRequiredWorkspace_parses_execution_policy_json() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        WorkingDirectoryEntity directory = directory("dir-1", WorkspaceScope.USER_PRIVATE, ResourceOwnerType.UPSTREAM_USER);
        directory.setClientAppId("capp-1");
        directory.setUpstreamUserId("user-1");
        directory.setOwnerId("capp-1:user-1");
        directory.setQuotaJson("{\"maxBytes\":1048576}");
        directory.setRetentionPolicyJson("{\"days\":7}");
        directory.setConcurrencyPolicyJson("{\"maxWriters\":1}");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(workingDirectoryRepository.findByDirectoryId("dir-1")).thenReturn(Optional.of(directory));

        ResolvedWorkspaceResource result = resolver.resolveRequiredWorkspace(
                "tenant-1", "capp-1", "user-1", "dir-1");

        assertEquals(1048576, ((Map<?, ?>) result.quotaPolicy()).get("maxBytes"));
        assertEquals(7, ((Map<?, ?>) result.retentionPolicy()).get("days"));
        assertEquals(1, ((Map<?, ?>) result.concurrencyPolicy()).get("maxWriters"));
    }

    @Test
    void resolveRequiredWorkspace_rejects_user_private_directory_for_other_upstream_user() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        WorkingDirectoryEntity directory = directory("dir-1", WorkspaceScope.USER_PRIVATE, ResourceOwnerType.UPSTREAM_USER);
        directory.setClientAppId("capp-1");
        directory.setUpstreamUserId("user-2");
        directory.setOwnerId("capp-1:user-2");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(workingDirectoryRepository.findByDirectoryId("dir-1")).thenReturn(Optional.of(directory));

        assertThrows(SecurityException.class, () ->
                resolver.resolveRequiredWorkspace("tenant-1", "capp-1", "user-1", "dir-1"));
    }

    @Test
    void resolveRequiredWorkspace_allows_client_app_shared_directory_for_same_app() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        WorkingDirectoryEntity directory = directory("dir-2", WorkspaceScope.CLIENT_APP_SHARED, ResourceOwnerType.CLIENT_APP);
        directory.setClientAppId("capp-1");
        directory.setOwnerId("capp-1");
        directory.setRootRef("D:/workspace/app-shared");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(workingDirectoryRepository.findByDirectoryId("dir-2")).thenReturn(Optional.of(directory));

        ResolvedWorkspaceResource result = resolver.resolveRequiredWorkspace(
                "tenant-1", "capp-1", "user-1", "dir-2");

        assertEquals(WorkspaceScope.CLIENT_APP_SHARED, result.workspaceScope());
        assertEquals("D:/workspace/app-shared", result.workdir());
        assertEquals(List.of("D:/workspace/app-shared"), result.allowedDirs());
    }

    @Test
    void resolveRequiredWorkspace_allows_upstream_system_shared_directory_for_same_system() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        WorkingDirectoryEntity directory = directory("dir-3", WorkspaceScope.UPSTREAM_SYSTEM_SHARED, ResourceOwnerType.UPSTREAM_SYSTEM);
        directory.setOwnerId("system-1");
        directory.setRootRef("D:/workspace/system-shared");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(workingDirectoryRepository.findByDirectoryId("dir-3")).thenReturn(Optional.of(directory));

        ResolvedWorkspaceResource result = resolver.resolveRequiredWorkspace(
                "tenant-1", "capp-1", "user-1", "dir-3");

        assertEquals(WorkspaceScope.UPSTREAM_SYSTEM_SHARED, result.workspaceScope());
        assertEquals("D:/workspace/system-shared", result.workdir());
    }

    @Test
    void resolveOptionalWorkspaceForAgent_returns_empty_without_directory_id() {
        Optional<ResolvedWorkspaceResource> result = resolver.resolveOptionalWorkspaceForAgent(
                "tenant-1",
                "capp-1",
                "user-1",
                agentResource("agent-1", "dir-default"),
                " ");

        assertTrue(result.isEmpty());
    }

    @Test
    void resolveRequiredWorkspaceForAgent_allows_agent_default_directory_without_binding() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        WorkingDirectoryEntity directory = directory("dir-default", WorkspaceScope.CLIENT_APP_SHARED, ResourceOwnerType.CLIENT_APP);
        directory.setClientAppId("capp-1");
        directory.setOwnerId("capp-1");
        directory.setRootRef("D:/workspace/app-shared");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(workingDirectoryRepository.findByDirectoryId("dir-default")).thenReturn(Optional.of(directory));

        ResolvedWorkspaceResource result = resolver.resolveRequiredWorkspaceForAgent(
                "tenant-1",
                "capp-1",
                "user-1",
                agentResource("agent-1", "dir-default"),
                "dir-default");

        assertEquals("dir-default", result.directoryId());
        assertEquals("AGENT_DEFAULT_DIRECTORY:CLIENT_APP_SHARED", result.source());
    }

    @Test
    void resolveRequiredWorkspaceForAgent_allows_bound_non_default_directory() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        WorkingDirectoryEntity directory = directory("dir-bound", WorkspaceScope.CLIENT_APP_SHARED, ResourceOwnerType.CLIENT_APP);
        directory.setClientAppId("capp-1");
        directory.setOwnerId("capp-1");
        directory.setRootRef("D:/workspace/app-bound");
        AgentDirectoryBindingEntity binding = new AgentDirectoryBindingEntity();
        binding.setTenantId("tenant-1");
        binding.setAgentId("agent-1");
        binding.setDirectoryId("dir-bound");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(workingDirectoryRepository.findByDirectoryId("dir-bound")).thenReturn(Optional.of(directory));
        when(agentDirectoryBindingRepository.findByTenantIdAndAgentIdAndDirectoryId(
                "tenant-1", "agent-1", "dir-bound")).thenReturn(Optional.of(binding));

        ResolvedWorkspaceResource result = resolver.resolveRequiredWorkspaceForAgent(
                "tenant-1",
                "capp-1",
                "user-1",
                agentResource("agent-1", "dir-default"),
                "dir-bound");

        assertEquals("dir-bound", result.directoryId());
        assertEquals("AGENT_WORKSPACE_BINDING:CLIENT_APP_SHARED", result.source());
    }

    @Test
    void resolveRequiredWorkspaceForAgent_rejects_unbound_non_default_directory() {
        ClientAppEntity clientApp = clientApp("tenant-1", "capp-1", "system-1");
        WorkingDirectoryEntity directory = directory("dir-other", WorkspaceScope.CLIENT_APP_SHARED, ResourceOwnerType.CLIENT_APP);
        directory.setClientAppId("capp-1");
        directory.setOwnerId("capp-1");
        directory.setRootRef("D:/workspace/app-other");
        when(clientAppService.requireClientApp("tenant-1", "capp-1")).thenReturn(clientApp);
        when(workingDirectoryRepository.findByDirectoryId("dir-other")).thenReturn(Optional.of(directory));
        when(agentDirectoryBindingRepository.findByTenantIdAndAgentIdAndDirectoryId(
                "tenant-1", "agent-1", "dir-other")).thenReturn(Optional.empty());

        SecurityException error = assertThrows(SecurityException.class, () ->
                resolver.resolveRequiredWorkspaceForAgent(
                        "tenant-1",
                        "capp-1",
                        "user-1",
                        agentResource("agent-1", "dir-default"),
                        "dir-other"));

        assertTrue(error.getMessage().contains("not bound to agent"));
    }

    private ClientAppEntity clientApp(String tenantId, String clientAppId, String upstreamSystemId) {
        ClientAppEntity entity = new ClientAppEntity();
        entity.setTenantId(tenantId);
        entity.setClientAppId(clientAppId);
        entity.setUpstreamSystemId(upstreamSystemId);
        return entity;
    }

    private WorkingDirectoryEntity directory(String directoryId,
                                             WorkspaceScope workspaceScope,
                                             ResourceOwnerType ownerType) {
        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(directoryId);
        entity.setTenantId("tenant-1");
        entity.setOwnerType(ownerType);
        entity.setOwnerId("owner-1");
        entity.setWorkspaceScope(workspaceScope);
        entity.setResolverType(WorkingDirectoryResolverType.DELEGATED);
        entity.setEnabled(true);
        entity.setReadOnly(false);
        entity.setWorkerId("worker-1");
        entity.setPath("D:/workspace/default");
        return entity;
    }

    private LlmModelConfigDTO model(String modelConfigId) {
        return model(modelConfigId, null);
    }

    private LlmModelConfigDTO model(String modelConfigId, List<String> availableModels) {
        LlmModelConfigDTO dto = new LlmModelConfigDTO();
        dto.setId(modelConfigId);
        dto.setModelName(modelConfigId + "-name");
        dto.setWorkerBackend("LANGGRAPH_BIZ");
        dto.setAvailableModels(availableModels);
        return dto;
    }

    private CodingAgentEntity agent(String agentId, ResourceOwnerType ownerType, String ownerId) {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId(agentId);
        entity.setTenantId("tenant-1");
        entity.setOwnerType(ownerType);
        entity.setOwnerId(ownerId);
        entity.setUserId("admin-1");
        entity.setName("Agent");
        entity.setAgentType(BusinessAgentBundleService.AGENT_TYPE_LANGGRAPH);
        entity.setEnabled(true);
        return entity;
    }

    private BizWorkerPoolEntity pool(String poolId, ResourceOwnerType ownerType, String ownerId) {
        BizWorkerPoolEntity entity = new BizWorkerPoolEntity();
        entity.setPoolId(poolId);
        entity.setTenantId("tenant-1");
        entity.setOwnerType(ownerType);
        entity.setOwnerId(ownerId);
        entity.setWorkerBackend("LANGGRAPH_BIZ");
        entity.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        entity.setHealthStatus(BizWorkerPoolService.HEALTHY);
        return entity;
    }

    private A2AgentResourceResolver.ResolvedAgentResource agentResource(String agentId, String defaultDirectoryId) {
        return agentResource(agentId, null, defaultDirectoryId);
    }

    private A2AgentResourceResolver.ResolvedAgentResource agentResource(
            String agentId,
            String defaultModelConfigId,
            String defaultDirectoryId) {
        return new A2AgentResourceResolver.ResolvedAgentResource(
                agentId,
                ResourceOwnerType.CLIENT_APP,
                "capp-1",
                "capp-1",
                "skill-1",
                "pool-1",
                ResourceOwnerType.PLATFORM,
                "tenant-1",
                "WORKER_POOL:PLATFORM",
                "LANGGRAPH_BIZ",
                null,
                null,
                null,
                null,
                defaultModelConfigId,
                null,
                defaultDirectoryId,
                "AGENT:CLIENT_APP");
    }
}
