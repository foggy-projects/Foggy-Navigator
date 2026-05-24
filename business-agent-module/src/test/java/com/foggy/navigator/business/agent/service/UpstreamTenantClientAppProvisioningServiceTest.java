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
        when(modelConfigGrantService.listGrants(anyString(), anyString()))
                .thenReturn(List.of(defaultModelGrant()));
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
        assertNotNull(result.getClientAppId());
        assertEquals("tms-tenant-3", result.getClientAppName());
        assertEquals("tms-x3-tenant-3", result.getCapabilityDomain());
        assertEquals("cak-secret", result.getClientAppKey());
        assertEquals("cas-secret", result.getClientAppSecret());
        assertEquals("cac-secret", result.getControlApiKey());
        assertEquals("model-1", result.getModelConfigId());
        assertEquals("tms-root-agent", result.getRootAgentId());
        assertEquals("tms.navigator.agent", result.getSkillId());
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

    private EnsureUpstreamTenantClientAppForm form(boolean rotateCredentials) {
        EnsureUpstreamTenantClientAppForm form = new EnsureUpstreamTenantClientAppForm();
        form.setSourceSystem("TMS");
        form.setSourceTenantId("3");
        form.setClientAppName("tms-tenant-3");
        form.setCapabilityDomain("tms-x3-tenant-3");
        form.setTenantName("TMS tenant 3");
        form.setAgentBundleCode("tms-root-agent");
        form.setWorkerPoolId("biz-worker");
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
