package com.foggy.navigator.session.registry;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UnifiedAgentResolverTest {

    @Mock
    private A2aAgentProvider provider1;
    @Mock
    private A2aAgentProvider provider2;
    @Mock
    private LlmModelManager llmModelManager;

    private UnifiedAgentResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new UnifiedAgentResolver(List.of(provider1, provider2), llmModelManager);
    }

    // ---- listAgents ----

    @Test
    void listAgents_aggregatesFromAllProviders() {
        AgentResolveContext context = ctx("user-1");
        when(provider1.listAgentCards(context)).thenReturn(List.of(card("a1")));
        when(provider2.listAgentCards(context)).thenReturn(List.of(card("a2")));

        List<A2aAgentCard> result = resolver.listAgents(context);

        assertEquals(2, result.size());
        assertEquals("a1", result.get(0).getId());
        assertEquals("a2", result.get(1).getId());
    }

    @Test
    void listAgents_emptyProviders_returnsEmpty() {
        UnifiedAgentResolver emptyResolver = new UnifiedAgentResolver(List.of(), llmModelManager);

        List<A2aAgentCard> result = emptyResolver.listAgents(ctx("user-1"));

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void listAgents_passesContextToProviders() {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        when(provider1.listAgentCards(any(AgentResolveContext.class))).thenReturn(List.of());
        when(provider2.listAgentCards(any(AgentResolveContext.class))).thenReturn(List.of());

        resolver.listAgents(context);

        ArgumentCaptor<AgentResolveContext> captor1 = ArgumentCaptor.forClass(AgentResolveContext.class);
        ArgumentCaptor<AgentResolveContext> captor2 = ArgumentCaptor.forClass(AgentResolveContext.class);
        verify(provider1).listAgentCards(captor1.capture());
        verify(provider2).listAgentCards(captor2.capture());

        assertEquals("user-1", captor1.getValue().getUserId());
        assertEquals("tenant-1", captor1.getValue().getTenantId());
        assertEquals("user-1", captor2.getValue().getUserId());
        assertEquals("tenant-1", captor2.getValue().getTenantId());
    }

    // ---- resolveAgent ----

    @Test
    void resolveAgent_firstMatchWins() {
        AgentResolveContext context = ctx("user-1");
        A2aAgent agent1 = mock(A2aAgent.class);
        A2aAgent agent2 = mock(A2aAgent.class);

        when(provider1.resolveAgent("a1", context)).thenReturn(Optional.of(agent1));

        Optional<A2aAgent> result = resolver.resolveAgent("a1", context);

        assertTrue(result.isPresent());
        assertSame(agent1, result.get());
        verify(provider2, never()).resolveAgent(eq("a1"), any(AgentResolveContext.class));
    }

    @Test
    void resolveAgent_notFound_returnsEmpty() {
        AgentResolveContext context = ctx("user-1");
        when(provider1.resolveAgent("unknown", context)).thenReturn(Optional.empty());
        when(provider2.resolveAgent("unknown", context)).thenReturn(Optional.empty());

        Optional<A2aAgent> result = resolver.resolveAgent("unknown", context);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolveAgent_secondProviderHasIt() {
        AgentResolveContext context = ctx("user-1");
        A2aAgent agent = mock(A2aAgent.class);

        when(provider1.resolveAgent("a2", context)).thenReturn(Optional.empty());
        when(provider2.resolveAgent("a2", context)).thenReturn(Optional.of(agent));

        Optional<A2aAgent> result = resolver.resolveAgent("a2", context);

        assertTrue(result.isPresent());
        assertSame(agent, result.get());
    }

    @Test
    void resolveAgent_prefersProviderFromModelConfig() {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .modelConfigId("cfg-1")
                .build();
        A2aAgent agent = mock(A2aAgent.class);
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setId("cfg-1");
        config.setWorkerBackend("OPENAI_CODEX");

        when(llmModelManager.getModelConfig("cfg-1")).thenReturn(Optional.of(config));
        when(provider1.getProviderType()).thenReturn("claude-worker");
        when(provider2.getProviderType()).thenReturn("codex-worker");
        when(provider2.resolveAgent("a2", context)).thenReturn(Optional.of(agent));

        Optional<A2aAgent> result = resolver.resolveAgent("a2", context);

        assertTrue(result.isPresent());
        assertSame(agent, result.get());
        verify(provider1, never()).resolveAgent(eq("a2"), any(AgentResolveContext.class));
    }

    // ---- getProviderType ----

    @Test
    void getProviderType_returnsMatchingType() {
        AgentResolveContext context = ctx("user-1");
        when(provider1.resolveAgent("a1", context)).thenReturn(Optional.of(mock(A2aAgent.class)));
        when(provider1.getProviderType()).thenReturn("claude-worker");

        Optional<String> result = resolver.getProviderType("a1", context);

        assertTrue(result.isPresent());
        assertEquals("claude-worker", result.get());
    }

    @Test
    void getProviderType_notFound_returnsEmpty() {
        AgentResolveContext context = ctx("user-1");
        when(provider1.resolveAgent("unknown", context)).thenReturn(Optional.empty());
        when(provider2.resolveAgent("unknown", context)).thenReturn(Optional.empty());

        Optional<String> result = resolver.getProviderType("unknown", context);

        assertTrue(result.isEmpty());
    }

    // ---- helpers ----

    private A2aAgentCard card(String id) {
        return A2aAgentCard.builder().id(id).name("Agent " + id).build();
    }

    private AgentResolveContext ctx(String userId) {
        return AgentResolveContext.builder().userId(userId).build();
    }
}
