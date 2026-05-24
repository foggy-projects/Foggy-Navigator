package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.form.BindAgentModelForm;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.repository.BusinessAgentModelBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.AgentModelBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentModelBindingServiceTest {

    private BusinessAgentModelBindingRepository bindingRepository;
    private BusinessCodingAgentRepository agentRepository;
    private ClientAppService clientAppService;
    private ClientAppModelConfigGrantService modelConfigGrantService;
    private LlmModelManager llmModelManager;
    private AgentModelBindingService service;

    @BeforeEach
    void setUp() {
        bindingRepository = mock(BusinessAgentModelBindingRepository.class);
        agentRepository = mock(BusinessCodingAgentRepository.class);
        clientAppService = mock(ClientAppService.class);
        modelConfigGrantService = mock(ClientAppModelConfigGrantService.class);
        llmModelManager = mock(LlmModelManager.class);
        service = new AgentModelBindingService(
                bindingRepository,
                agentRepository,
                clientAppService,
                modelConfigGrantService,
                llmModelManager);

        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(clientAppAgent()));
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void bind_requiresClientAppOwnedAgentAndGrantedModel() {
        when(modelConfigGrantService.resolveEffectiveModelConfigId("tenant-1", "capp-1", "cfg-1"))
                .thenReturn("cfg-1");
        when(bindingRepository.findByTenantIdAndAgentIdAndModelConfigId("tenant-1", "agent-1", "cfg-1"))
                .thenReturn(Optional.empty());
        when(llmModelManager.getModelConfig("cfg-1")).thenReturn(Optional.of(model("cfg-1")));

        var result = service.bind("tenant-1", "capp-1", "agent-1", bindForm(" cfg-1 "));

        assertEquals("tenant-1", result.getTenantId());
        assertEquals("capp-1", result.getClientAppId());
        assertEquals("agent-1", result.getAgentId());
        assertEquals("cfg-1", result.getModelConfigId());
        assertEquals("cfg-1-name", result.getModelConfigName());
        assertEquals("LANGGRAPH_BIZ", result.getWorkerBackend());
        assertFalse(result.isDefaultModel());
        verify(clientAppService).requireActiveClientApp("tenant-1", "capp-1");
        verify(bindingRepository).save(argThat(binding ->
                "tenant-1".equals(binding.getTenantId())
                        && "agent-1".equals(binding.getAgentId())
                        && "cfg-1".equals(binding.getModelConfigId())));
    }

    @Test
    void bind_rejectsSystemOwnedAgentFromClientAppPlane() {
        CodingAgentEntity agent = clientAppAgent();
        agent.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        agent.setOwnerId("ups-1");
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agent));

        assertThrows(SecurityException.class,
                () -> service.bind("tenant-1", "capp-1", "agent-1", bindForm("cfg-1")));
    }

    @Test
    void unbind_rejectsDefaultModelBinding() {
        assertThrows(IllegalArgumentException.class,
                () -> service.unbind("tenant-1", "capp-1", "agent-1", " cfg-default "));

        verify(bindingRepository, never()).deleteByTenantIdAndAgentIdAndModelConfigId(anyString(), anyString(), anyString());
    }

    @Test
    void unbind_deletesNonDefaultBinding() {
        service.unbind("tenant-1", "capp-1", "agent-1", " cfg-1 ");

        verify(bindingRepository).deleteByTenantIdAndAgentIdAndModelConfigId("tenant-1", "agent-1", "cfg-1");
    }

    @Test
    void setDefault_updatesAgentDefaultAndEnsuresBinding() {
        CodingAgentEntity agent = clientAppAgent();
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agent));
        when(modelConfigGrantService.resolveEffectiveModelConfigId("tenant-1", "capp-1", "cfg-2"))
                .thenReturn("cfg-2");
        when(bindingRepository.findByTenantIdAndAgentIdAndModelConfigId("tenant-1", "agent-1", "cfg-2"))
                .thenReturn(Optional.empty());
        when(agentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.setDefault("tenant-1", "capp-1", "agent-1", bindForm("cfg-2"));

        assertEquals("cfg-2", result.getModelConfigId());
        assertTrue(result.isDefaultModel());
        verify(agentRepository).save(argThat(saved -> "cfg-2".equals(saved.getDefaultModelConfigId())));
    }

    @Test
    void bindSystemOwned_requiresSystemOwnedAgentAndModel() {
        CodingAgentEntity agent = systemAgent();
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agent));
        when(bindingRepository.findByTenantIdAndAgentIdAndModelConfigId("tenant-1", "agent-1", "cfg-1"))
                .thenReturn(Optional.empty());
        when(llmModelManager.getModelConfig("cfg-1")).thenReturn(Optional.of(systemModel("cfg-1")));

        var result = service.bindSystemOwned("tenant-1", principal(), "agent-1", bindForm("cfg-1"));

        assertEquals("cfg-1", result.getModelConfigId());
        verify(bindingRepository).save(argThat(binding ->
                "tenant-1".equals(binding.getTenantId())
                        && "agent-1".equals(binding.getAgentId())
                        && "cfg-1".equals(binding.getModelConfigId())));
    }

    @Test
    void bindSystemOwned_rejectsClientAppOwnedAgent() {
        assertThrows(SecurityException.class,
                () -> service.bindSystemOwned("tenant-1", principal(), "agent-1", bindForm("cfg-1")));
    }

    private BindAgentModelForm bindForm(String modelConfigId) {
        BindAgentModelForm form = new BindAgentModelForm();
        form.setModelConfigId(modelConfigId);
        return form;
    }

    private CodingAgentEntity clientAppAgent() {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setTenantId("tenant-1");
        agent.setClientAppId("capp-1");
        agent.setAgentId("agent-1");
        agent.setOwnerType(ResourceOwnerType.CLIENT_APP);
        agent.setOwnerId("capp-1");
        agent.setDefaultModelConfigId("cfg-default");
        agent.setEnabled(true);
        return agent;
    }

    private CodingAgentEntity systemAgent() {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setTenantId("tenant-1");
        agent.setAgentId("agent-1");
        agent.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        agent.setOwnerId("ups-1");
        agent.setDefaultModelConfigId("cfg-default");
        agent.setEnabled(true);
        return agent;
    }

    private LlmModelConfigDTO model(String modelConfigId) {
        LlmModelConfigDTO dto = new LlmModelConfigDTO();
        dto.setId(modelConfigId);
        dto.setName(modelConfigId + "-name");
        dto.setWorkerBackend("LANGGRAPH_BIZ");
        return dto;
    }

    private LlmModelConfigDTO systemModel(String modelConfigId) {
        LlmModelConfigDTO dto = model(modelConfigId);
        dto.setTenantId("tenant-1");
        dto.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        dto.setOwnerId("ups-1");
        dto.setEnabled(true);
        return dto;
    }

    private UpstreamClientAppAdminPrincipal principal() {
        return UpstreamClientAppAdminPrincipal.builder()
                .upstreamSystemId("ups-1")
                .authorizedTenantIds(Set.of("tenant-1"))
                .build();
    }
}
