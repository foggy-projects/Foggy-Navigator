package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.EnsureUpstreamTenantClientAppForm;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpstreamTenantClientAppProvisioningServiceTest {

    private final Map<String, ClientAppEntity> clientAppsByUpstreamKey = new HashMap<>();
    private final Map<String, CodingAgentEntity> agentsByKey = new HashMap<>();
    private ClientAppService clientAppService;
    private ClientAppModelConfigGrantService modelConfigGrantService;
    private SkillRegistryService skillRegistryService;
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
        when(modelConfigGrantService.resolveEffectiveModelConfigId(anyString(), anyString(), isNull()))
                .thenReturn("model-1");
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
                new ObjectMapper());
    }

    @Test
    void ensureCreatesClientAppAndReturnsInitialCredentials() {
        var result = service.ensure(form(false), "operator-1");

        assertTrue(result.isCreated());
        assertFalse(result.isRotated());
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
        verify(skillRegistryService).syncSkillBundle(eq("nav_tms_3"), eq("operator-1"), any());
    }

    @Test
    void ensureExistingClientAppDoesNotExposeSecretsUnlessRotating() {
        var first = service.ensure(form(false), "operator-1");
        var second = service.ensure(form(false), "operator-1");

        assertFalse(second.isCreated());
        assertFalse(second.isRotated());
        assertEquals(first.getClientAppId(), second.getClientAppId());
        assertNull(second.getClientAppKey());
        assertNull(second.getClientAppSecret());
        assertNull(second.getControlApiKey());
        verify(clientAppService, times(1)).issueRuntimeCredential(anyString(), anyString(), any());
        verify(clientAppService, times(1)).issueControlCredential(anyString(), anyString(), anyString(), any());
    }

    @Test
    void ensureExistingClientAppRotatesCredentialsWhenRequested() {
        service.ensure(form(false), "operator-1");

        var rotated = service.ensure(form(true), "operator-1");

        assertFalse(rotated.isCreated());
        assertTrue(rotated.isRotated());
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

        var result = service.ensure(form, "operator-1");
        CodingAgentEntity rootAgent = agentsByKey.get(agentKey("tms-root-agent", "nav_tms_3"));

        assertNull(result.getWorkerPoolId());
        assertNotNull(rootAgent);
        assertNull(rootAgent.getWorkerId());
        assertTrue(result.getBlockers().isEmpty());
    }

    @Test
    void ensureWithoutWorkerPoolDoesNotClearExistingRootAgentWorker() {
        service.ensure(form(false), "operator-1");
        EnsureUpstreamTenantClientAppForm withoutWorkerPool = form(false);
        withoutWorkerPool.setWorkerPoolId(null);

        service.ensure(withoutWorkerPool, "operator-1");

        CodingAgentEntity rootAgent = agentsByKey.get(agentKey("tms-root-agent", "nav_tms_3"));
        assertEquals("biz-worker", rootAgent.getWorkerId());
    }

    @Test
    void ensureReturnsBlockerWhenDefaultModelGrantIsMissing() {
        when(modelConfigGrantService.resolveEffectiveModelConfigId(anyString(), anyString(), isNull()))
                .thenThrow(new IllegalArgumentException("default model config grant is required"));

        var result = service.ensure(form(false), "operator-1");

        assertNull(result.getModelConfigId());
        assertTrue(result.getBlockers().stream().anyMatch(item -> item.contains("modelConfigId is missing")));
        assertTrue(result.getBlockers().stream().anyMatch(item -> item.contains("defaultModelConfigId")));
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
