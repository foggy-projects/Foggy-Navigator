package com.foggy.navigator.session.registry;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aArtifact;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Test
    void resolveAgent_routesAppServerBackendOnlyToAppServerProvider() {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .modelConfigId("cfg-app-server")
                .build();
        A2aAgent agent = mock(A2aAgent.class);
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setId("cfg-app-server");
        config.setWorkerBackend("OPENAI_CODEX_APP_SERVER");

        when(llmModelManager.getModelConfig("cfg-app-server")).thenReturn(Optional.of(config));
        when(provider1.getProviderType()).thenReturn("codex-worker");
        when(provider2.getProviderType()).thenReturn("codex-app-server-worker");
        when(provider2.resolveAgent("codex-agent", context)).thenReturn(Optional.of(agent));

        Optional<A2aAgent> result = resolver.resolveAgent("codex-agent", context);

        assertSame(agent, result.orElseThrow());
        verify(provider1, never()).resolveAgent(anyString(), any(AgentResolveContext.class));
    }

    @Test
    void resolveAgent_mappedSdkProviderMissing_doesNotScanAppServerProvider() {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .modelConfigId("cfg-sdk")
                .build();
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setId("cfg-sdk");
        config.setWorkerBackend("OPENAI_CODEX");

        when(llmModelManager.getModelConfig("cfg-sdk")).thenReturn(Optional.of(config));
        when(provider1.getProviderType()).thenReturn("claude-worker");
        when(provider2.getProviderType()).thenReturn("codex-app-server-worker");

        assertTrue(resolver.resolveAgent("codex-agent", context).isEmpty());
        verify(provider1, never()).resolveAgent(anyString(), any(AgentResolveContext.class));
        verify(provider2, never()).resolveAgent(anyString(), any(AgentResolveContext.class));
    }

    @Test
    void resolveAgent_mappedAppServerProviderMissing_doesNotScanSdkProvider() {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .modelConfigId("cfg-app")
                .build();
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setId("cfg-app");
        config.setWorkerBackend("OPENAI_CODEX_APP_SERVER");

        when(llmModelManager.getModelConfig("cfg-app")).thenReturn(Optional.of(config));
        when(provider1.getProviderType()).thenReturn("claude-worker");
        when(provider2.getProviderType()).thenReturn("codex-worker");

        assertTrue(resolver.resolveAgent("codex-agent", context).isEmpty());
        verify(provider1, never()).resolveAgent(anyString(), any(AgentResolveContext.class));
        verify(provider2, never()).resolveAgent(anyString(), any(AgentResolveContext.class));
    }

    @Test
    void resolveAgent_unmappedModelConfig_doesNotScanAnyProvider() {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .modelConfigId("cfg-missing")
                .build();
        when(llmModelManager.getModelConfig("cfg-missing")).thenReturn(Optional.empty());

        assertTrue(resolver.resolveAgent("codex-agent", context).isEmpty());
        verify(provider1, never()).resolveAgent(anyString(), any(AgentResolveContext.class));
        verify(provider2, never()).resolveAgent(anyString(), any(AgentResolveContext.class));
    }

    @Test
    void resolveAgent_prefersLangGraphProviderFromModelConfig() {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .modelConfigId("cfg-langgraph")
                .build();
        A2aAgent agent = mock(A2aAgent.class);
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setId("cfg-langgraph");
        config.setWorkerBackend("LANGGRAPH_BIZ");

        when(llmModelManager.getModelConfig("cfg-langgraph")).thenReturn(Optional.of(config));
        when(provider1.getProviderType()).thenReturn("claude-worker");
        when(provider2.getProviderType()).thenReturn("langgraph-biz-worker");
        when(provider2.resolveAgent("biz-agent", context)).thenReturn(Optional.of(agent));

        Optional<A2aAgent> result = resolver.resolveAgent("biz-agent", context);

        assertTrue(result.isPresent());
        assertSame(agent, result.get());
        verify(provider1, never()).resolveAgent(eq("biz-agent"), any(AgentResolveContext.class));
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

    @Test
    void getProviderType_mappedAppServerProviderMissing_doesNotScanSdkProvider() {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .modelConfigId("cfg-app")
                .build();
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setId("cfg-app");
        config.setWorkerBackend("OPENAI_CODEX_APP_SERVER");

        when(llmModelManager.getModelConfig("cfg-app")).thenReturn(Optional.of(config));
        when(provider1.getProviderType()).thenReturn("codex-worker");
        when(provider2.getProviderType()).thenReturn("claude-worker");

        assertTrue(resolver.getProviderType("codex-agent", context).isEmpty());
        verify(provider1, never()).resolveAgent(anyString(), any(AgentResolveContext.class));
        verify(provider2, never()).resolveAgent(anyString(), any(AgentResolveContext.class));
    }

    @Test
    void inMemoryA2aFixture_isDiscoverableAndExercisesTaskLifecycle() {
        A2aAgentProvider fixtureProvider = new InMemoryA2aFixtureProvider();
        UnifiedAgentResolver fixtureResolver = new UnifiedAgentResolver(
                List.of(fixtureProvider), llmModelManager);
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("fixture-user")
                .tenantId("fixture-tenant")
                .requestSource("TEST")
                .build();

        List<A2aAgentCard> cards = fixtureResolver.listAgents(context);
        A2aAgent agent = fixtureResolver.resolveAgent("test-fixture-agent", context).orElseThrow();
        A2aTask task = agent.sendTask(A2aMessage.builder()
                .role("user")
                .contextId("fixture-context")
                .parts(List.of(A2aPart.text("fixture ping")))
                .build());

        assertEquals(List.of("test-fixture-agent"),
                cards.stream().map(A2aAgentCard::getId).toList());
        assertEquals(A2aTaskState.COMPLETED, task.getStatus().getState());
        assertEquals("fixture-context", task.getContextId());
        assertEquals("[Test Fixture] fixture ping",
                task.getArtifacts().get(0).getParts().get(0).getText());
        assertSame(task, agent.getTask(task.getId()).orElseThrow());

        agent.cancelTask(task.getId());

        assertEquals(A2aTaskState.CANCELED,
                agent.getTask(task.getId()).orElseThrow().getStatus().getState());
    }

    // ---- helpers ----

    private A2aAgentCard card(String id) {
        return A2aAgentCard.builder().id(id).name("Agent " + id).build();
    }

    private AgentResolveContext ctx(String userId) {
        return AgentResolveContext.builder().userId(userId).build();
    }

    /**
     * Test-only replacement for the retired production Echo addon. It keeps
     * provider discovery and the basic A2A lifecycle reproducible without
     * registering a synthetic Agent in the launcher.
     */
    private static final class InMemoryA2aFixtureProvider implements A2aAgentProvider {

        private static final String AGENT_ID = "test-fixture-agent";
        private final A2aAgent agent = new InMemoryA2aFixtureAgent();

        @Override
        public List<A2aAgentCard> listAgentCards(String userId) {
            return List.of(agent.getAgentCard());
        }

        @Override
        public Optional<A2aAgent> resolveAgent(String agentId, String userId) {
            return AGENT_ID.equals(agentId) ? Optional.of(agent) : Optional.empty();
        }

        @Override
        public String getProviderType() {
            return "test-fixture";
        }
    }

    private static final class InMemoryA2aFixtureAgent implements A2aAgent {

        private final Map<String, A2aTask> tasks = new LinkedHashMap<>();
        private int nextTaskId = 1;

        @Override
        public A2aAgentCard getAgentCard() {
            return A2aAgentCard.builder()
                    .id(InMemoryA2aFixtureProvider.AGENT_ID)
                    .name("Test Fixture Agent")
                    .description("In-memory A2A fixture")
                    .version("test")
                    .build();
        }

        @Override
        public A2aTask sendTask(A2aMessage message) {
            String prompt = message.getParts() == null
                    ? "(no parts)"
                    : message.getParts().stream()
                            .filter(part -> "text".equals(part.getType()) && part.getText() != null)
                            .map(A2aPart::getText)
                            .findFirst()
                            .orElse("(no text)");
            A2aTask task = A2aTask.builder()
                    .id("fixture-task-" + nextTaskId++)
                    .contextId(message.getContextId())
                    .status(status(A2aTaskState.COMPLETED, "Fixture completed"))
                    .artifacts(List.of(A2aArtifact.builder()
                            .artifactId("fixture-result")
                            .name("Fixture Result")
                            .parts(List.of(A2aPart.text("[Test Fixture] " + prompt)))
                            .build()))
                    .metadata(Map.of("providerType", "test-fixture"))
                    .build();
            tasks.put(task.getId(), task);
            return task;
        }

        @Override
        public Optional<A2aTask> getTask(String taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public void cancelTask(String taskId) {
            A2aTask task = tasks.get(taskId);
            if (task != null) {
                task.setStatus(status(A2aTaskState.CANCELED, "Fixture canceled"));
            }
        }

        private A2aTaskStatus status(A2aTaskState state, String description) {
            return A2aTaskStatus.builder()
                    .state(state)
                    .description(description)
                    .timestamp(Instant.now())
                    .build();
        }
    }
}
