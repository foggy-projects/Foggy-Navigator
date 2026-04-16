package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaudeWorkerAgentProviderTest {

    @Mock
    private CodingAgentRepository agentRepository;
    @Mock
    private ClaudeTaskService taskService;
    @Mock
    private WorkingDirectoryRepository directoryRepository;
    @Mock
    private LlmModelManager llmModelManager;

    private ClaudeWorkerAgentProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ClaudeWorkerAgentProvider(agentRepository, taskService, directoryRepository, llmModelManager, null);
    }

    private CodingAgentEntity claudeAgent(String agentId, String userId) {
        CodingAgentEntity e = new CodingAgentEntity();
        e.setAgentId(agentId);
        e.setUserId(userId);
        e.setName("agent-" + agentId);
        e.setAgentType("LOCAL_CLAUDE_WORKER");
        e.setWorkerId("worker-1");
        return e;
    }

    // ===== Resolution chain tests (收窄后：agentId → name) =====

    @Nested
    class ResolutionChain {

        @Test
        void resolveAgent_byAgentId_directHit() {
            CodingAgentEntity entity = claudeAgent("agent-1", "user-1");
            when(agentRepository.findByAgentIdAndUserId("agent-1", "user-1")).thenReturn(Optional.of(entity));

            Optional<A2aAgent> result = provider.resolveAgent("agent-1", "user-1");

            assertTrue(result.isPresent());
            assertEquals("agent-1", result.get().getAgentCard().getId());
        }

        @Test
        void resolveAgent_byName_secondLevel() {
            CodingAgentEntity entity = claudeAgent("agent-1", "user-1");
            when(agentRepository.findByAgentIdAndUserId("my-agent", "user-1")).thenReturn(Optional.empty());
            when(agentRepository.findByNameAndUserId("my-agent", "user-1")).thenReturn(Optional.of(entity));

            Optional<A2aAgent> result = provider.resolveAgent("my-agent", "user-1");

            assertTrue(result.isPresent());
            assertEquals("agent-1", result.get().getAgentCard().getId());
        }

        @Test
        void resolveAgent_allPathsExhausted_returnsEmpty() {
            when(agentRepository.findByAgentIdAndUserId("unknown", "user-1")).thenReturn(Optional.empty());
            when(agentRepository.findByNameAndUserId("unknown", "user-1")).thenReturn(Optional.empty());

            Optional<A2aAgent> result = provider.resolveAgent("unknown", "user-1");

            assertTrue(result.isEmpty());
        }
    }

    // ===== Tenant isolation tests =====

    @Nested
    class TenantIsolation {

        @Test
        void listAgentCards_uiContext_usesUserId() {
            CodingAgentEntity entity = claudeAgent("agent-1", "user-1");
            when(agentRepository.findByUserIdOrderByCreatedAtDesc("user-1")).thenReturn(List.of(entity));

            AgentResolveContext ctx = AgentResolveContext.builder()
                    .userId("user-1").requestSource("UI").build();

            List<A2aAgentCard> cards = provider.listAgentCards(ctx);

            assertEquals(1, cards.size());
            verify(agentRepository).findByUserIdOrderByCreatedAtDesc("user-1");
            verify(agentRepository, never()).findByTenantIdOrderByCreatedAtDesc(anyString());
        }

        @Test
        void listAgentCards_openApiContext_usesTenantId() {
            CodingAgentEntity entity = claudeAgent("agent-1", "user-1");
            entity.setTenantId("tenant-1");
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc("tenant-1")).thenReturn(List.of(entity));

            AgentResolveContext ctx = AgentResolveContext.builder()
                    .tenantId("tenant-1").requestSource("OPEN_API").build();

            List<A2aAgentCard> cards = provider.listAgentCards(ctx);

            assertEquals(1, cards.size());
            verify(agentRepository).findByTenantIdOrderByCreatedAtDesc("tenant-1");
        }

        @Test
        void resolveAgent_openApiContext_usesResolveByTenant() {
            CodingAgentEntity entity = claudeAgent("agent-1", "user-1");
            entity.setTenantId("tenant-1");
            when(agentRepository.findByAgentId("agent-1")).thenReturn(Optional.of(entity));

            AgentResolveContext ctx = AgentResolveContext.builder()
                    .tenantId("tenant-1").requestSource("OPEN_API").build();

            Optional<A2aAgent> result = provider.resolveAgent("agent-1", ctx);

            assertTrue(result.isPresent());
            verify(agentRepository).findByAgentId("agent-1");
        }

        @Test
        void resolveAgentByTenant_wrongTenant_returnsEmpty() {
            CodingAgentEntity entity = claudeAgent("agent-1", "user-1");
            entity.setTenantId("tenant-A");
            when(agentRepository.findByAgentId("agent-1")).thenReturn(Optional.of(entity));

            Optional<A2aAgent> result = provider.resolveAgentByTenant("agent-1", "tenant-B");

            assertTrue(result.isEmpty());
        }

        @Test
        void listAgentCardsByTenant_filtersLocalClaudeWorker() {
            CodingAgentEntity claude = claudeAgent("agent-1", "user-1");
            CodingAgentEntity codex = new CodingAgentEntity();
            codex.setAgentId("agent-2");
            codex.setAgentType("LOCAL_CODEX_WORKER");
            codex.setName("codex-agent");
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc("t1")).thenReturn(List.of(claude, codex));

            List<A2aAgentCard> cards = provider.listAgentCardsByTenant("t1");

            assertEquals(1, cards.size());
            assertEquals("agent-1", cards.get(0).getId());
        }

        @Test
        void listAgentCardsByTenant_prefersModelConfigProviderInference() {
            CodingAgentEntity dynamicClaude = claudeAgent("agent-1", "user-1");
            dynamicClaude.setAgentType("LOCAL_CODEX_WORKER");
            dynamicClaude.setDefaultModelConfigId("cfg-claude");
            LlmModelConfigDTO config = new LlmModelConfigDTO();
            config.setId("cfg-claude");
            config.setWorkerBackend("CLAUDE_CODE");
            when(agentRepository.findByTenantIdOrderByCreatedAtDesc("t1")).thenReturn(List.of(dynamicClaude));
            when(llmModelManager.getModelConfig("cfg-claude")).thenReturn(Optional.of(config));

            List<A2aAgentCard> cards = provider.listAgentCardsByTenant("t1");

            assertEquals(1, cards.size());
            assertEquals("agent-1", cards.get(0).getId());
        }
    }

    // ===== Provider type test =====

    @Test
    void getProviderType_returnsClaudeWorker() {
        assertEquals("claude-worker", provider.getProviderType());
    }
}
