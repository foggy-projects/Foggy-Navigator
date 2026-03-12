package com.foggy.navigator.session.registry;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DefaultA2aAgentRegistry 单元测试 — L1
 */
class DefaultA2aAgentRegistryTest {

    // ---- 构造器 ----

    @Test
    void constructor_nullProviders_handlesGracefully() {
        DefaultA2aAgentRegistry registry = new DefaultA2aAgentRegistry(null);
        List<A2aAgentCard> cards = registry.listAgents("u1");
        assertNotNull(cards);
        assertTrue(cards.isEmpty());
    }

    @Test
    void constructor_emptyProviders() {
        DefaultA2aAgentRegistry registry = new DefaultA2aAgentRegistry(Collections.emptyList());
        assertTrue(registry.listAgents("u1").isEmpty());
    }

    // ---- listAgents ----

    @Test
    void listAgents_aggregatesFromAllProviders() {
        A2aAgentProvider p1 = mock(A2aAgentProvider.class);
        A2aAgentProvider p2 = mock(A2aAgentProvider.class);
        when(p1.listAgentCards("u1")).thenReturn(List.of(card("a1"), card("a2")));
        when(p2.listAgentCards("u1")).thenReturn(List.of(card("a3")));

        DefaultA2aAgentRegistry registry = new DefaultA2aAgentRegistry(List.of(p1, p2));

        List<A2aAgentCard> result = registry.listAgents("u1");
        assertEquals(3, result.size());
    }

    @Test
    void listAgents_providerReturnsEmpty() {
        A2aAgentProvider p1 = mock(A2aAgentProvider.class);
        when(p1.listAgentCards("u1")).thenReturn(List.of());

        DefaultA2aAgentRegistry registry = new DefaultA2aAgentRegistry(List.of(p1));

        assertTrue(registry.listAgents("u1").isEmpty());
    }

    // ---- resolveAgent ----

    @Test
    void resolveAgent_firstMatchWins() {
        A2aAgent agentFromP1 = mock(A2aAgent.class);
        A2aAgent agentFromP2 = mock(A2aAgent.class);

        A2aAgentProvider p1 = mock(A2aAgentProvider.class);
        A2aAgentProvider p2 = mock(A2aAgentProvider.class);
        when(p1.resolveAgent("a1", "u1")).thenReturn(Optional.of(agentFromP1));
        when(p2.resolveAgent("a1", "u1")).thenReturn(Optional.of(agentFromP2));

        DefaultA2aAgentRegistry registry = new DefaultA2aAgentRegistry(List.of(p1, p2));

        Optional<A2aAgent> result = registry.resolveAgent("a1", "u1");
        assertTrue(result.isPresent());
        assertSame(agentFromP1, result.get()); // first provider wins
    }

    @Test
    void resolveAgent_notFound_returnsEmpty() {
        A2aAgentProvider p1 = mock(A2aAgentProvider.class);
        when(p1.resolveAgent("unknown", "u1")).thenReturn(Optional.empty());

        DefaultA2aAgentRegistry registry = new DefaultA2aAgentRegistry(List.of(p1));

        assertTrue(registry.resolveAgent("unknown", "u1").isEmpty());
    }

    @Test
    void resolveAgent_secondProviderHasIt() {
        A2aAgent agent = mock(A2aAgent.class);

        A2aAgentProvider p1 = mock(A2aAgentProvider.class);
        A2aAgentProvider p2 = mock(A2aAgentProvider.class);
        when(p1.resolveAgent("a2", "u1")).thenReturn(Optional.empty());
        when(p2.resolveAgent("a2", "u1")).thenReturn(Optional.of(agent));

        DefaultA2aAgentRegistry registry = new DefaultA2aAgentRegistry(List.of(p1, p2));

        Optional<A2aAgent> result = registry.resolveAgent("a2", "u1");
        assertTrue(result.isPresent());
        assertSame(agent, result.get());
    }

    // ---- listByProviderType ----

    @Test
    void listByProviderType_filtersCorrectly() {
        A2aAgentProvider cwProvider = mock(A2aAgentProvider.class);
        A2aAgentProvider ohProvider = mock(A2aAgentProvider.class);
        when(cwProvider.getProviderType()).thenReturn("claude-worker");
        when(ohProvider.getProviderType()).thenReturn("openhands");
        when(cwProvider.listAgentCards("u1")).thenReturn(List.of(card("cw-1"), card("cw-2")));
        when(ohProvider.listAgentCards("u1")).thenReturn(List.of(card("oh-1")));

        DefaultA2aAgentRegistry registry = new DefaultA2aAgentRegistry(List.of(cwProvider, ohProvider));

        List<A2aAgentCard> cwCards = registry.listByProviderType("claude-worker", "u1");
        assertEquals(2, cwCards.size());

        List<A2aAgentCard> ohCards = registry.listByProviderType("openhands", "u1");
        assertEquals(1, ohCards.size());
    }

    @Test
    void listByProviderType_noMatch_empty() {
        A2aAgentProvider p1 = mock(A2aAgentProvider.class);
        when(p1.getProviderType()).thenReturn("claude-worker");

        DefaultA2aAgentRegistry registry = new DefaultA2aAgentRegistry(List.of(p1));

        assertTrue(registry.listByProviderType("nonexistent", "u1").isEmpty());
    }

    // ---- helper ----

    private A2aAgentCard card(String id) {
        return A2aAgentCard.builder().id(id).name("Agent " + id).build();
    }
}
