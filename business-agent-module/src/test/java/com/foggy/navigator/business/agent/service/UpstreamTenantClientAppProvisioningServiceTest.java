package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.EnsureUpstreamTenantClientAppForm;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpstreamTenantClientAppProvisioningServiceTest {

    private final Map<String, ClientAppEntity> clientAppsByUpstreamKey = new HashMap<>();
    private final Map<String, CodingAgentEntity> agentsByKey = new HashMap<>();
    private ClientAppService clientAppService;
    private ClientAppModelConfigGrantService modelConfigGrantService;
    private SkillRegistryService skillRegistryService;
    private AgentDefaultBindingService agentDefaultBindingService;
    private A2AgentResourceResolver agentResourceResolver;
    private UpstreamTenantClientAppProvisioningService service;

    @BeforeEach
    void setUp() {
        ClientAppRepository clientAppRepository = mock(ClientAppRepository.class);
        when(clientAppRepository.findByTenantIdAndUpstreamSystemIdAndUpstreamClientAppNamespaceAndUpstreamRef(
                anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(clientAppsByUpstreamKey.get(upstreamKey(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)))));
        when(clientAppRepository.save(any())).thenAnswer(inv -> {
            ClientAppEntity app = inv.getArgument(0);
            clientAppsByUpstreamKey.put(upstreamKey(app.getTenantId(), app.getUpstreamSystemId(),
                    app.getUpstreamClientAppNamespace(), app.getUpstreamRef()), app);
            return app;
        });

        BusinessCodingAgentRepository agentRepository = mock(BusinessCodingAgentRepository.class);
        when(agentRepository.findByAgentIdAndTenantId(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(agentsByKey.get(agentKey(inv.getArgument(0), inv.getArgument(1)))));
        when(agentRepository.save(any())).thenAnswer(inv -> {
            CodingAgentEntity agent = inv.getArgument(0);
            agentsByKey.put(agentKey(agent.getAgentId(), agent.getTenantId()), agent);
            return agent;
        });

        clientAppService = mock(ClientAppService.class);
        modelConfigGrantService = mock(ClientAppModelConfigGrantService.class);
        skillRegistryService = mock(SkillRegistryService.class);
        agentDefaultBindingService = mock(AgentDefaultBindingService.class);
        agentResourceResolver = mock(A2AgentResourceResolver.class);
        when(modelConfigGrantService.listGrants(anyString(), anyString()))
                .thenReturn(List.of(defaultModelGrant()));
        when(agentResourceResolver.resolveRequiredAgent(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new A2AgentResourceResolver.ResolvedAgentResource(
                        inv.getArgument(3),
                        ResourceOwnerType.CLIENT_APP,
                        inv.getArgument(1),
                        inv.getArgument(1),
                        "tms.navigator.agent",
                        null,
                        null,
                        null,
                        null,
                        ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND,
                        "biz-worker",
                        ResourceOwnerType.UPSTREAM_SYSTEM,
                        "TMS",
                        "PHYSICAL_WORKER_IDENTITY:UPSTREAM_SYSTEM",
                        "model-1",
                        null,
                        "dir-tms-3",
                        "AGENT:CLIENT_APP"));
        when(clientAppService.issueRuntimeCredential(anyString(), anyString(), any())).thenAnswer(inv -> {
            IssuedCredentialDTO dto = new IssuedCredentialDTO();
            dto.setTenantId(inv.getArgument(0));
            dto.setClientAppId(inv.getArgument(1));
            dto.setAppKey("cak-secret");
            dto.setSecret("cas-secret");
            return dto;
        });
        when(clientAppService.issueControlCredential(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            IssuedCredentialDTO dto = new IssuedCredentialDTO();
            dto.setTenantId(inv.getArgument(0));
            dto.setClientAppId(inv.getArgument(2));
            dto.setControlApiKey("cac-secret");
            return dto;
        });

        service = new UpstreamTenantClientAppProvisioningService(
                clientAppRepository,
                clientAppService,
                modelConfigGrantService,
                agentRepository,
                skillRegistryService,
                agentDefaultBindingService,
                agentResourceResolver,
                new ObjectMapper());
    }

    @Test
    void ensureCreatesClientAppAndReturnsInitialCredentials() {
        var result = service.ensure(form(false), principal("nav_tms_3"));

        assertTrue(result.isCreated());
        assertFalse(result.isRotated());
        assertTrue(result.isCredentialsReplayable());
        assertEquals(UpstreamTenantClientAppProvisioningService.STATUS_READY, result.getStatus());
        assertNull(result.getErrorCode());
        assertEquals("nav_tms_3", result.getNavigatorTenantId());
        assertEquals("nav_tms_3", result.getTargetNavigatorTenantId());
        assertNotNull(result.getClientAppId());
        assertEquals("tms-tenant-3", result.getClientAppName());
        assertEquals("tms-x3-tenant-3", result.getCapabilityDomain());
        assertEquals("tms-x3-tenant-3", result.getClientAppCapabilityDomain());
        assertEquals("TMS", result.getUpstreamSystemId());
        assertEquals("3", result.getSourceTenantId());
        assertEquals("3", result.getUpstreamRef());
        assertEquals("TMS", result.getUpstreamNamespace());
        assertEquals("cak-secret", result.getClientAppKey());
        assertEquals("cas-secret", result.getClientAppSecret());
        assertEquals("cac-secret", result.getControlApiKey());
        assertEquals("model-1", result.getModelConfigId());
        assertEquals("tms-root-agent", result.getAgentCode());
        assertEquals("tms-root-agent", result.getRootAgentId());
        assertEquals("tms.navigator.agent", result.getSkillId());
        assertEquals(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND, result.getWorkerBackend());
        assertEquals("biz-worker", result.getPhysicalWorkerId());
        assertEquals("dir-tms-3", result.getDirectoryId());
        assertEquals("http://127.0.0.1:3161", result.getBizWorkerBaseUrl());
        assertTrue(result.isActivationReady());
        assertTrue(result.getMissingFields().isEmpty());
        assertTrue(result.getRequiredScopes().contains(UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE));
        assertTrue(result.getActualScopes().contains(UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE));
        assertTrue(result.getAuthorizedTenantIds().contains("nav_tms_3"));
        assertTrue(result.getBlockers().isEmpty());
        CodingAgentEntity rootAgent = agentsByKey.get(agentKey("tms-root-agent", "nav_tms_3"));
        assertNotNull(rootAgent);
        assertEquals(ResourceOwnerType.CLIENT_APP, rootAgent.getOwnerType());
        assertEquals(result.getClientAppId(), rootAgent.getOwnerId());
        assertEquals(result.getClientAppId(), rootAgent.getClientAppId());
        assertTrue(rootAgent.getEnabled());
        verify(agentDefaultBindingService).ensureDefaults(rootAgent);
        verify(skillRegistryService).syncSkillBundle(eq("nav_tms_3"), eq("upstream-admin:ucaac-1"), any());
    }

    @Test
    void ensureExistingClientAppDoesNotExposeSecretsUnlessRotating() {
        var first = service.ensure(form(false), principal("nav_tms_3"));
        var second = service.ensure(form(false), principal("nav_tms_3"));

        assertFalse(second.isCreated());
        assertFalse(second.isRotated());
        assertFalse(second.isCredentialsReplayable());
        assertEquals(UpstreamTenantClientAppProvisioningService.STATUS_CREDENTIALS_NOT_REPLAYABLE, second.getStatus());
        assertEquals(UpstreamTenantClientAppProvisioningService.STATUS_CREDENTIALS_NOT_REPLAYABLE, second.getErrorCode());
        assertTrue(second.getMessage().contains("rotateCredentials=true"));
        assertEquals(first.getClientAppId(), second.getClientAppId());
        assertNull(second.getClientAppKey());
        assertNull(second.getClientAppSecret());
        assertNull(second.getControlApiKey());
        verify(clientAppService, times(1)).issueRuntimeCredential(anyString(), anyString(), any());
        verify(clientAppService, times(1)).issueControlCredential(anyString(), anyString(), anyString(), any());
    }

    @Test
    void ensureExistingClientAppRotatesCredentialsWhenRequested() {
        service.ensure(form(false), principal("nav_tms_3"));

        var rotated = service.ensure(form(true), principal("nav_tms_3"));

        assertFalse(rotated.isCreated());
        assertTrue(rotated.isRotated());
        assertTrue(rotated.isCredentialsReplayable());
        assertEquals(UpstreamTenantClientAppProvisioningService.STATUS_READY, rotated.getStatus());
        assertNull(rotated.getErrorCode());
        assertEquals("cak-secret", rotated.getClientAppKey());
        assertEquals("cas-secret", rotated.getClientAppSecret());
        assertEquals("cac-secret", rotated.getControlApiKey());
        verify(clientAppService, times(2)).issueRuntimeCredential(anyString(), anyString(), any());
        verify(clientAppService, times(2)).issueControlCredential(anyString(), anyString(), anyString(), any());
    }

    @Test
    void ensureWithoutWorkerPoolDoesNotBlockSingleBizWorkerMvpRouting() {
        EnsureUpstreamTenantClientAppForm form = form(false);
        form.setWorkerPoolId(null);

        var result = service.ensure(form, principal("nav_tms_3"));
        CodingAgentEntity rootAgent = agentsByKey.get(agentKey("tms-root-agent", "nav_tms_3"));

        assertNull(result.getWorkerPoolId());
        assertNotNull(rootAgent);
        assertNull(rootAgent.getWorkerId());
        assertTrue(result.getBlockers().isEmpty());
        assertFalse(result.isActivationReady());
        assertTrue(result.getMissingFields().contains("physicalWorkerId"));
    }

    @Test
    void ensureWithoutWorkerPoolDoesNotClearExistingRootAgentWorker() {
        service.ensure(form(false), principal("nav_tms_3"));
        EnsureUpstreamTenantClientAppForm withoutWorkerPool = form(false);
        withoutWorkerPool.setWorkerPoolId(null);

        service.ensure(withoutWorkerPool, principal("nav_tms_3"));

        CodingAgentEntity rootAgent = agentsByKey.get(agentKey("tms-root-agent", "nav_tms_3"));
        assertEquals("biz-worker", rootAgent.getWorkerId());
    }

    @Test
    void ensureReturnsBlockerWhenDefaultModelGrantIsMissing() {
        when(modelConfigGrantService.listGrants(anyString(), anyString()))
                .thenReturn(List.of());

        var result = service.ensure(form(false), principal("nav_tms_3"));

        assertNull(result.getModelConfigId());
        assertTrue(result.getBlockers().stream().anyMatch(item -> item.contains("modelConfigId is missing")));
        assertTrue(result.getBlockers().stream().anyMatch(item -> item.contains("defaultModelConfigId")));
        verify(modelConfigGrantService, never()).resolveEffectiveModelConfigId(anyString(), anyString(), any());
    }

    @Test
    void ensureReturnsStructuredBlockerWhenExplicitModelConfigIsInvisible() {
        EnsureUpstreamTenantClientAppForm form = form(true);
        form.setModelConfigId("foreign-model");
        when(modelConfigGrantService.grantModelConfig(eq("nav_tms_3"), anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("model config is not visible to this ClientApp"));

        var result = service.ensure(form, principal("nav_tms_3"));

        assertFalse(result.isActivationReady());
        assertEquals(UpstreamTenantClientAppProvisioningService.ERROR_MODEL_CONFIG_RESOURCE, result.getErrorCode());
        assertNull(result.getModelConfigId());
        assertTrue(result.getMissingFields().contains("modelConfig.visibility"));
        assertTrue(result.getBlockers().stream()
                .anyMatch(item -> item.contains("model config is not visible to this ClientApp")));
        assertTrue(result.getRemediationHint().contains("UPSTREAM_SYSTEM/TMS"));
        CodingAgentEntity rootAgent = agentsByKey.get(agentKey("tms-root-agent", "nav_tms_3"));
        assertNotNull(rootAgent);
        assertEquals("biz-worker", rootAgent.getWorkerId());
        assertNull(rootAgent.getDefaultModelConfigId());
    }

    @Test
    void ensureRejectsMismatchedSourceSystemForAdminCredential() {
        assertThrows(SecurityException.class, () -> service.ensure(form(false), UpstreamClientAppAdminPrincipal.builder()
                .credentialId("ucaac-1")
                .upstreamSystemId("ERP")
                .authorizedClientAppNamespace("TMS")
                .authorizedTenantIds(Set.of("nav_tms_3"))
                .scopes(Set.of())
                .build()));
    }

    @Test
    void ensureRejectsUnauthorizedDerivedNavigatorTenant() {
        assertThrows(SecurityException.class, () -> service.ensure(form(false), principal("nav_tms_4")));
    }

    @Test
    void ensureAllowsUpstreamSystemScopedAdminCredentialForDerivedTenant() {
        var result = service.ensure(form(false), principal("TMS"));

        assertTrue(result.isCreated());
        assertEquals("nav_tms_3", result.getNavigatorTenantId());
        assertEquals("tms-tenant-3", result.getClientAppName());
    }

    @Test
    void ensureAllowsSourceTenantScopedAdminCredentialForDerivedTenant() {
        var result = service.ensure(form(false), principal("3"));

        assertTrue(result.isCreated());
        assertEquals("nav_tms_3", result.getNavigatorTenantId());
    }

    @Test
    void ensureReturnsActivationPolicyOverrides() {
        EnsureUpstreamTenantClientAppForm form = form(false);
        form.setUpstreamRef("TMS-3");
        form.setAgentCode("tms.ops-root-agent");
        form.setWorkerBackend("langgraph-biz");
        form.setPhysicalWorkerId("worker-physical-1");
        form.setDirectoryId("dir-override");

        var result = service.ensure(form, principal("nav_tms_3"));

        assertEquals("TMS-3", result.getUpstreamRef());
        assertEquals("tms.ops-root-agent", result.getAgentCode());
        assertEquals("tms.ops-root-agent", result.getRootAgentId());
        assertEquals("LANGGRAPH_BIZ", result.getWorkerBackend());
        assertEquals("worker-physical-1", result.getPhysicalWorkerId());
        assertEquals("dir-override", result.getDirectoryId());
        assertTrue(result.isActivationReady());
        CodingAgentEntity rootAgent = agentsByKey.get(agentKey("tms.ops-root-agent", "nav_tms_3"));
        assertEquals("worker-physical-1", rootAgent.getWorkerId());
        assertEquals("dir-override", rootAgent.getDefaultDirectoryId());
    }

    @Test
    void ensureMarksActivationNotReadyWhenPhysicalWorkerOwnerIsMissing() {
        EnsureUpstreamTenantClientAppForm form = form(false);
        form.setPhysicalWorkerId("dev-langgraph-worker-20260504123547");
        when(agentResourceResolver.resolveRequiredAgent(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException(
                        "physical worker owner is not configured: dev-langgraph-worker-20260504123547"));

        var result = service.ensure(form, principal("nav_tms_3"));

        assertFalse(result.isActivationReady());
        assertEquals(UpstreamTenantClientAppProvisioningService.ERROR_RUNTIME_AGENT_RESOURCE, result.getErrorCode());
        assertTrue(result.getMissingFields().contains("physicalWorker.owner"));
        assertTrue(result.getBlockers().stream()
                .anyMatch(item -> item.contains("physical worker owner is not configured")));
        assertTrue(result.getRemediationHint().contains("ownerType=PLATFORM ownerId=platform"));
        assertTrue(result.getRemediationHint().contains("ownerType=UPSTREAM_SYSTEM ownerId=TMS"));
    }

    @Test
    void ensureMarksActivationNotReadyWhenWorkingDirectoryTenantMismatch() {
        EnsureUpstreamTenantClientAppForm form = form(false);
        form.setDirectoryId("20260525-8fa8");
        when(agentResourceResolver.resolveRequiredWorkspaceForAgent(
                anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new SecurityException("working directory tenant mismatch: 20260525-8fa8"));

        var result = service.ensure(form, principal("nav_tms_3"));

        assertFalse(result.isActivationReady());
        assertEquals(UpstreamTenantClientAppProvisioningService.ERROR_WORKSPACE_RESOURCE, result.getErrorCode());
        assertTrue(result.getMissingFields().contains("directory.tenant"));
        assertTrue(result.getBlockers().stream()
                .anyMatch(item -> item.contains("working directory tenant mismatch: 20260525-8fa8")));
        assertTrue(result.getRemediationHint().contains("nav_tms_3"));
        assertTrue(result.getRemediationHint().contains("rootAgentId=tms-root-agent"));
        assertTrue(result.getRemediationHint().contains("20260525-8fa8"));
    }

    private EnsureUpstreamTenantClientAppForm form(boolean rotateCredentials) {
        EnsureUpstreamTenantClientAppForm form = new EnsureUpstreamTenantClientAppForm();
        form.setSourceSystem("TMS");
        form.setSourceTenantId("3");
        form.setClientAppName("tms-tenant-3");
        form.setCapabilityDomain("tms-x3-tenant-3");
        form.setTenantName("TMS tenant 3");
        form.setAgentBundleCode("tms-root-agent");
        form.setWorkerPoolId("biz-worker");
        form.setDirectoryId("dir-tms-3");
        form.setBizWorkerBaseUrl("http://127.0.0.1:3161");
        form.setRotateCredentials(rotateCredentials);
        return form;
    }

    private UpstreamClientAppAdminPrincipal principal(String... tenantIds) {
        return UpstreamClientAppAdminPrincipal.builder()
                .credentialId("ucaac-1")
                .upstreamSystemId("TMS")
                .authorizedClientAppNamespace("TMS")
                .authorizedTenantIds(Set.of(tenantIds))
                .scopes(Set.of(
                        UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE,
                        UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE))
                .build();
    }

    private ClientAppModelConfigGrantDTO defaultModelGrant() {
        ClientAppModelConfigGrantDTO grant = new ClientAppModelConfigGrantDTO();
        grant.setId(1L);
        grant.setTenantId("nav_tms_3");
        grant.setClientAppId("capp-1");
        grant.setModelConfigId("model-1");
        grant.setStatus(ClientAppModelConfigGrantService.STATUS_ENABLED);
        grant.setIsDefault(true);
        grant.setWorkerBackend(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        return grant;
    }

    private static String upstreamKey(String tenantId,
                                      String upstreamSystemId,
                                      String namespace,
                                      String upstreamRef) {
        return tenantId + "|" + upstreamSystemId + "|" + namespace + "|" + upstreamRef;
    }

    private static String agentKey(String agentId, String tenantId) {
        return tenantId + "|" + agentId;
    }
}
