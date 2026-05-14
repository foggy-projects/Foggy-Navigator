package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.repository.CodexCodingAgentRepository;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexWorkerAgentProviderTest {

    @Mock
    private CodexCodingAgentRepository agentRepository;
    @Mock
    private CodexTaskService taskService;
    @Mock
    private LlmModelManager llmModelManager;
    @Mock
    private WorkerManagementFacade workerManagementFacade;

    private CodexWorkerAgentProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CodexWorkerAgentProvider(agentRepository,
                taskService, llmModelManager, null, workerManagementFacade);
    }

    @Test
    void resolveAgent_byAgentId_directHit() {
        CodingAgentEntity entity = codexAgent("agent-1", "user-1", "codex-bot");
        when(agentRepository.findByAgentIdAndUserId("agent-1", "user-1")).thenReturn(Optional.of(entity));

        Optional<A2aAgent> result = provider.resolveAgent("agent-1", "user-1");

        assertTrue(result.isPresent());
        assertEquals("agent-1", result.get().getAgentCard().getId());
    }

    @Test
    void resolveAgent_byName_secondLevel() {
        CodingAgentEntity entity = codexAgent("agent-1", "user-1", "codex-bot");
        when(agentRepository.findByAgentIdAndUserId("codex-bot", "user-1")).thenReturn(Optional.empty());
        when(agentRepository.findByNameAndUserId("codex-bot", "user-1")).thenReturn(Optional.of(entity));

        Optional<A2aAgent> result = provider.resolveAgent("codex-bot", "user-1");

        assertTrue(result.isPresent());
        assertEquals("agent-1", result.get().getAgentCard().getId());
    }

    @Test
    void resolveAgent_notFound_returnsEmpty() {
        when(agentRepository.findByAgentIdAndUserId("unknown", "user-1")).thenReturn(Optional.empty());
        when(agentRepository.findByNameAndUserId("unknown", "user-1")).thenReturn(Optional.empty());

        Optional<A2aAgent> result = provider.resolveAgent("unknown", "user-1");

        assertTrue(result.isEmpty());
    }

    @Test
    void listAgentCards_filtersLocalCodexWorker() {
        CodingAgentEntity codexEntity = codexAgent("agent-codex", "user-1", "codex-bot");
        CodingAgentEntity claudeEntity = new CodingAgentEntity();
        claudeEntity.setAgentId("agent-claude");
        claudeEntity.setUserId("user-1");
        claudeEntity.setName("claude-bot");
        claudeEntity.setAgentType("LOCAL_CLAUDE_WORKER");

        when(agentRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(codexEntity, claudeEntity));

        List<A2aAgentCard> cards = provider.listAgentCards("user-1");

        assertEquals(1, cards.size());
        assertEquals("agent-codex", cards.get(0).getId());
    }

    @Test
    void listAgentCards_openApiContext_usesTenantId() {
        CodingAgentEntity codexEntity = codexAgent("agent-codex", "user-1", "codex-bot");
        codexEntity.setTenantId("tenant-1");
        when(agentRepository.findByTenantIdOrderByCreatedAtDesc("tenant-1"))
                .thenReturn(List.of(codexEntity));

        List<A2aAgentCard> cards = provider.listAgentCards(AgentResolveContext.builder()
                .tenantId("tenant-1")
                .requestSource("OPEN_API")
                .build());

        assertEquals(1, cards.size());
        assertEquals("agent-codex", cards.get(0).getId());
    }

    @Test
    void resolveAgent_openApiContext_usesTenantResolution() {
        CodingAgentEntity codexEntity = codexAgent("agent-codex", "user-1", "codex-bot");
        codexEntity.setTenantId("tenant-1");
        when(agentRepository.findByAgentIdAndTenantId("agent-codex", "tenant-1")).thenReturn(Optional.of(codexEntity));

        Optional<A2aAgent> result = provider.resolveAgent("agent-codex", AgentResolveContext.builder()
                .tenantId("tenant-1")
                .requestSource("OPEN_API")
                .build());

        assertTrue(result.isPresent());
        verify(agentRepository).findByAgentIdAndTenantId("agent-codex", "tenant-1");
        verify(agentRepository, never()).findByAgentId("agent-codex");
    }

    @Test
    void listAgentCards_prefersModelConfigProviderInference() {
        CodingAgentEntity dynamicCodex = codexAgent("agent-codex", "user-1", "codex-bot");
        dynamicCodex.setAgentType("LOCAL_CLAUDE_WORKER");
        dynamicCodex.setDefaultModelConfigId("cfg-codex");
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setId("cfg-codex");
        config.setWorkerBackend("OPENAI_CODEX");
        when(agentRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(dynamicCodex));
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(config));

        List<A2aAgentCard> cards = provider.listAgentCards("user-1");

        assertEquals(1, cards.size());
        assertEquals("agent-codex", cards.get(0).getId());
    }

    @Test
    void getProviderType_returnsCodexWorker() {
        assertEquals("codex-worker", provider.getProviderType());
    }

    // ---- helpers ----

    private CodingAgentEntity codexAgent(String agentId, String userId, String name) {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId(agentId);
        entity.setUserId(userId);
        entity.setName(name);
        entity.setAgentType("LOCAL_CODEX_WORKER");
        entity.setWorkerId("worker-1");
        return entity;
    }
}
