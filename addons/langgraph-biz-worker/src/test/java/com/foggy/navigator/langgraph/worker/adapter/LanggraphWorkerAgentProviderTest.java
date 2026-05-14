package com.foggy.navigator.langgraph.worker.adapter;

import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphCodingAgentRepository;
import com.foggy.navigator.langgraph.worker.service.LanggraphTaskService;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LanggraphWorkerAgentProviderTest {

    @Mock
    private LanggraphCodingAgentRepository agentRepository;
    @Mock
    private LanggraphTaskService taskService;
    @Mock
    private LlmModelManager llmModelManager;

    @Test
    void resolveOpenApiAgentUsesTenantScopedAgentId() {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("tms-x3-agent-v305");
        entity.setTenantId("tenant-tms");
        entity.setUserId("control-user");
        entity.setName("TMS Agent");
        entity.setAgentType("LOCAL_LANGGRAPH_WORKER");

        when(agentRepository.findByAgentIdAndTenantId("tms-x3-agent-v305", "tenant-tms"))
                .thenReturn(Optional.of(entity));

        LanggraphWorkerAgentProvider provider = new LanggraphWorkerAgentProvider(
                agentRepository,
                taskService,
                llmModelManager,
                null);

        var result = provider.resolveAgent("tms-x3-agent-v305", AgentResolveContext.builder()
                .tenantId("tenant-tms")
                .userId("upstream-user")
                .requestSource("OPEN_API")
                .build());

        assertTrue(result.isPresent());
        verify(agentRepository).findByAgentIdAndTenantId("tms-x3-agent-v305", "tenant-tms");
        verify(agentRepository, never()).findByAgentId("tms-x3-agent-v305");
    }
}
