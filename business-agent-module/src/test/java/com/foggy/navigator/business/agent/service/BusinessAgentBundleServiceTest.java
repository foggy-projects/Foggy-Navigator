package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.SkillBundleDTO;
import com.foggy.navigator.business.agent.model.form.SyncBusinessAgentBundleForm;
import com.foggy.navigator.business.agent.model.form.SyncSkillBundleForm;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessAgentBundleServiceTest {

    @Mock
    private BusinessCodingAgentRepository agentRepository;
    @Mock
    private SkillRegistryService skillRegistryService;
    @Mock
    private ClientAppService clientAppService;
    @Mock
    private ClientAppModelConfigGrantService modelConfigGrantService;

    private BusinessAgentBundleService service;

    @BeforeEach
    void setUp() {
        service = new BusinessAgentBundleService(
                agentRepository,
                skillRegistryService,
                clientAppService,
                modelConfigGrantService,
                new ObjectMapper());
    }

    @Test
    void syncAgentBundle_createsAgentAndPublicSkillBundle() {
        SyncBusinessAgentBundleForm form = new SyncBusinessAgentBundleForm();
        form.setClientAppId("app_01");
        form.setAgentId("world-sim.bug-coordinator.decision.v1");
        form.setName("World Sim Bug Coordinator");
        form.setDescription("Decision agent");
        form.setWorkerId("worker_01");
        form.setDefaultModelConfigId("model_01");
        form.setMarkdownBody("# Agent");

        when(modelConfigGrantService.resolveEffectiveModelConfigId("tenant_01", "app_01", "model_01"))
                .thenReturn("model_01");
        when(agentRepository.findByAgentIdAndTenantId(form.getAgentId(), "tenant_01")).thenReturn(Optional.empty());
        when(agentRepository.save(any(CodingAgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillRegistryService.syncSkillBundle(eq("tenant_01"), eq("admin_01"), any(SyncSkillBundleForm.class)))
                .thenAnswer(inv -> {
                    SyncSkillBundleForm skillForm = inv.getArgument(2);
                    SkillBundleDTO dto = new SkillBundleDTO();
                    dto.setClientAppId(skillForm.getClientAppId());
                    dto.setSkillId(skillForm.getSkillId());
                    dto.setStatus("ENABLED");
                    return dto;
                });

        var result = service.syncAgentBundle("tenant_01", "admin_01", form);

        assertEquals(form.getAgentId(), result.getAgentId());
        assertEquals(form.getAgentId(), result.getSkillId());
        assertEquals("LOCAL_LANGGRAPH_WORKER", result.getAgentType());
        assertEquals("model_01", result.getDefaultModelConfigId());

        ArgumentCaptor<CodingAgentEntity> agentCaptor = ArgumentCaptor.forClass(CodingAgentEntity.class);
        verify(agentRepository).save(agentCaptor.capture());
        CodingAgentEntity agent = agentCaptor.getValue();
        assertEquals("tenant_01", agent.getTenantId());
        assertEquals("admin_01", agent.getUserId());
        assertEquals("worker_01", agent.getWorkerId());
        assertEquals("LOCAL_LANGGRAPH_WORKER", agent.getAgentType());
        assertTrue(agent.getSkills().contains("world-sim.bug-coordinator.decision.v1"));
        assertTrue(agent.getAgentProfile().contains("\"domain\":\"BUSINESS_AGENT\""));
        assertTrue(agent.getAgentProfile().contains("\"clientAppId\":\"app_01\""));
        assertTrue(agent.getAgentProfile().contains("\"skillId\":\"world-sim.bug-coordinator.decision.v1\""));
        assertEquals(agent.getAgentProfile(), result.getAgentProfile());

        ArgumentCaptor<SyncSkillBundleForm> skillCaptor = ArgumentCaptor.forClass(SyncSkillBundleForm.class);
        verify(skillRegistryService).syncSkillBundle(eq("tenant_01"), eq("admin_01"), skillCaptor.capture());
        SyncSkillBundleForm skillForm = skillCaptor.getValue();
        assertEquals("CLIENT_APP_PUBLIC", skillForm.getScope());
        assertEquals("app_01", skillForm.getClientAppId());
        assertEquals(form.getAgentId(), skillForm.getSkillId());
        assertEquals("# Agent", skillForm.getMarkdownBody());
    }

    @Test
    void syncAgentBundle_allowsSameAgentIdInDifferentTenants() {
        SyncBusinessAgentBundleForm form = new SyncBusinessAgentBundleForm();
        form.setClientAppId("app_01");
        form.setAgentCode("agent_01");
        form.setName("Agent");
        form.setWorkerId("worker_01");

        CodingAgentEntity existing = new CodingAgentEntity();
        existing.setAgentId("agent_01");
        existing.setTenantId("tenant_other");

        when(modelConfigGrantService.resolveEffectiveModelConfigId("tenant_01", "app_01", null))
                .thenReturn("model_01");
        when(agentRepository.findByAgentIdAndTenantId("agent_01", "tenant_01")).thenReturn(Optional.empty());
        when(agentRepository.save(any(CodingAgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillRegistryService.syncSkillBundle(eq("tenant_01"), eq("admin_01"), any(SyncSkillBundleForm.class)))
                .thenAnswer(inv -> {
                    SyncSkillBundleForm skillForm = inv.getArgument(2);
                    SkillBundleDTO dto = new SkillBundleDTO();
                    dto.setClientAppId(skillForm.getClientAppId());
                    dto.setSkillId(skillForm.getSkillId());
                    dto.setStatus("ENABLED");
                    return dto;
                });

        var result = service.syncAgentBundle("tenant_01", "admin_01", form);

        assertEquals("agent_01", result.getAgentId());
        ArgumentCaptor<CodingAgentEntity> agentCaptor = ArgumentCaptor.forClass(CodingAgentEntity.class);
        verify(agentRepository).save(agentCaptor.capture());
        assertEquals("tenant_01", agentCaptor.getValue().getTenantId());
    }
}
