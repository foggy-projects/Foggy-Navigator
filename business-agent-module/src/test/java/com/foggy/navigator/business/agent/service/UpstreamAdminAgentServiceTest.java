package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.form.UpstreamAgentForm;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpstreamAdminAgentServiceTest {

    @Mock
    private BusinessCodingAgentRepository agentRepository;
    @Mock
    private BizWorkerPoolRepository workerPoolRepository;
    @Mock
    private WorkingDirectoryRepository workingDirectoryRepository;
    @Mock
    private AgentDefaultBindingService agentDefaultBindingService;
    @Mock
    private LlmModelManager llmModelManager;

    private UpstreamAdminAgentService service;

    @BeforeEach
    void setUp() {
        service = new UpstreamAdminAgentService(
                agentRepository,
                workerPoolRepository,
                workingDirectoryRepository,
                agentDefaultBindingService,
                llmModelManager,
                new ObjectMapper());
    }

    @Test
    void create_createsSystemOwnedAgentAndDefaultModelBinding() {
        UpstreamAgentForm form = form();
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.empty());
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1")).thenReturn(Optional.of(pool(ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1")));
        when(llmModelManager.getModelConfig("model-1")).thenReturn(Optional.of(model(ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1")));
        when(agentRepository.save(any(CodingAgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.create("tenant-1", principal("ups-1"), form);

        assertEquals("agent-1", result.getAgentId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.getOwnerType());
        assertEquals("ups-1", result.getOwnerId());
        assertNull(result.getClientAppId());
        assertEquals("pool-1", result.getWorkerId());
        assertEquals("model-1", result.getDefaultModelConfigId());

        ArgumentCaptor<CodingAgentEntity> agentCaptor = ArgumentCaptor.forClass(CodingAgentEntity.class);
        verify(agentRepository).save(agentCaptor.capture());
        CodingAgentEntity saved = agentCaptor.getValue();
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, saved.getOwnerType());
        assertEquals("ups-1", saved.getOwnerId());
        assertTrue(saved.getAgentProfile().contains("UPSTREAM_SYSTEM_AGENT"));
        assertEquals("[]", saved.getSkills());
        verify(agentDefaultBindingService).ensureDefaults(saved);
    }

    @Test
    void create_rejectsWorkerPoolOwnedByOtherSystem() {
        UpstreamAgentForm form = form();
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.empty());
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1")).thenReturn(Optional.of(pool(ResourceOwnerType.UPSTREAM_SYSTEM, "ups-2")));

        assertThrows(SecurityException.class, () -> service.create("tenant-1", principal("ups-1"), form));
        verify(agentRepository, never()).save(any());
    }

    @Test
    void create_rejectsModelOwnedByOtherSystem() {
        UpstreamAgentForm form = form();
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1")).thenReturn(Optional.empty());
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1")).thenReturn(Optional.of(pool(ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1")));
        when(llmModelManager.getModelConfig("model-1")).thenReturn(Optional.of(model(ResourceOwnerType.UPSTREAM_SYSTEM, "ups-2")));

        assertThrows(SecurityException.class, () -> service.create("tenant-1", principal("ups-1"), form));
        verify(agentRepository, never()).save(any());
    }

    private UpstreamAgentForm form() {
        UpstreamAgentForm form = new UpstreamAgentForm();
        form.setAgentId("agent-1");
        form.setName("System Agent");
        form.setWorkerId("pool-1");
        form.setDefaultModelConfigId("model-1");
        return form;
    }

    private BizWorkerPoolEntity pool(ResourceOwnerType ownerType, String ownerId) {
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setTenantId("tenant-1");
        pool.setPoolId("pool-1");
        pool.setOwnerType(ownerType);
        pool.setOwnerId(ownerId);
        pool.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        return pool;
    }

    private LlmModelConfigDTO model(ResourceOwnerType ownerType, String ownerId) {
        LlmModelConfigDTO model = new LlmModelConfigDTO();
        model.setId("model-1");
        model.setTenantId("tenant-1");
        model.setOwnerType(ownerType);
        model.setOwnerId(ownerId);
        return model;
    }

    private UpstreamClientAppAdminPrincipal principal(String upstreamSystemId) {
        return UpstreamClientAppAdminPrincipal.builder()
                .credentialId("cred-1")
                .principalId("ups-principal")
                .upstreamSystemId(upstreamSystemId)
                .authorizedTenantIds(Set.of("tenant-1"))
                .scopes(Set.of(UpstreamBootstrapRequestService.SCOPE_AGENT_MANAGE))
                .build();
    }
}
