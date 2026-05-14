package com.foggy.navigator.gemini.worker.adapter;

import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.gemini.worker.repository.GeminiCodingAgentRepository;
import com.foggy.navigator.gemini.worker.service.GeminiTaskService;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeminiWorkerAgentProviderTest {

    @Mock
    private GeminiCodingAgentRepository agentRepository;
    @Mock
    private GeminiTaskService taskService;
    @Mock
    private LlmModelManager llmModelManager;
    @Mock
    private WorkerManagementFacade workerManagementFacade;

    private GeminiWorkerAgentProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GeminiWorkerAgentProvider(agentRepository, taskService, llmModelManager, null,
                workerManagementFacade);
    }

    @Test
    void resolveAgent_openApiContext_usesTenantScopedLookupOnly() {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("agent-gemini");
        entity.setTenantId("tenant-1");
        entity.setUserId("user-1");
        entity.setName("gemini-bot");
        entity.setAgentType("LOCAL_GEMINI_WORKER");
        entity.setWorkerId("worker-1");
        when(agentRepository.findByAgentIdAndTenantId("agent-gemini", "tenant-1"))
                .thenReturn(Optional.of(entity));

        Optional<A2aAgent> result = provider.resolveAgent("agent-gemini", AgentResolveContext.builder()
                .tenantId("tenant-1")
                .requestSource("OPEN_API")
                .build());

        assertTrue(result.isPresent());
        verify(agentRepository).findByAgentIdAndTenantId("agent-gemini", "tenant-1");
        verify(agentRepository, never()).findByAgentId("agent-gemini");
    }
}
