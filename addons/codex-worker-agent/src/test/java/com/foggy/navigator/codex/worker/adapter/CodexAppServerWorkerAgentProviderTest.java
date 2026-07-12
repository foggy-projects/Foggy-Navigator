package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.repository.CodexCodingAgentRepository;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodexAppServerWorkerAgentProviderTest {

    @Mock
    private CodexCodingAgentRepository agentRepository;
    @Mock
    private CodexTaskService taskService;
    @Mock
    private LlmModelManager llmModelManager;
    @Mock
    private WorkerManagementFacade workerManagementFacade;
    @Mock
    private AgentContextStore contextStore;

    private CodexAppServerWorkerAgentProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CodexAppServerWorkerAgentProvider(
                agentRepository, taskService, llmModelManager, contextStore, workerManagementFacade);
    }

    @Test
    void providerTypeIsIndependentAppServerRoute() {
        assertEquals(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, provider.getProviderType());
    }

    @Test
    void listsOnlyAgentsBackedByAppServerModelConfig() {
        CodingAgentEntity app = agent("agent-app", "cfg-app");
        CodingAgentEntity sdk = agent("agent-sdk", "cfg-sdk");
        when(agentRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(app, sdk));
        when(llmModelManager.getModelConfig("cfg-app"))
                .thenReturn(Optional.of(config("OPENAI_CODEX_APP_SERVER")));
        when(llmModelManager.getModelConfig("cfg-sdk"))
                .thenReturn(Optional.of(config("OPENAI_CODEX")));

        var cards = provider.listAgentCards("user-1");

        assertEquals(List.of("agent-app"), cards.stream().map(card -> card.getId()).toList());
        assertTrue(cards.get(0).getSkills().get(0).getDescription().contains("App Server"));
        assertTrue(cards.get(0).getSkills().get(0).getTags()
                .contains(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE));
    }

    @Test
    void legacyCodexAgentWithoutModelConfigDoesNotLeakIntoAppServerProvider() {
        CodingAgentEntity legacy = agent("agent-legacy", null);
        when(agentRepository.findByAgentIdAndUserId("agent-legacy", "user-1"))
                .thenReturn(Optional.of(legacy));

        assertTrue(provider.resolveAgent("agent-legacy", "user-1").isEmpty());
    }

    @Test
    void contextAffinityPersistsTheAppServerProvider() {
        CodingAgentEntity app = agent("agent-app", "cfg-app");
        when(agentRepository.findByAgentIdAndUserId("agent-app", "user-1"))
                .thenReturn(Optional.of(app));
        when(llmModelManager.getModelConfig("cfg-app"))
                .thenReturn(Optional.of(config("OPENAI_CODEX_APP_SERVER")));
        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any()))
                .thenReturn(CodexTaskDTO.builder()
                        .taskId("task-app")
                        .sessionId("session-app")
                        .codexThreadId("thread-app")
                        .workerId("worker-1")
                        .build());

        var resolved = provider.resolveAgent("agent-app", "user-1").orElseThrow();
        resolved.sendTask(A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("continue")))
                .contextId("ctx-app")
                .metadata(Map.of())
                .build());

        verify(contextStore).saveSessionRefFull(
                "ctx-app", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                "thread-app", "session-app", "user-1", "agent-app", null);
    }

    private CodingAgentEntity agent(String agentId, String modelConfigId) {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId(agentId);
        entity.setUserId("user-1");
        entity.setTenantId("tenant-1");
        entity.setName(agentId);
        entity.setAgentType("LOCAL_CODEX_WORKER");
        entity.setWorkerId("worker-1");
        entity.setDefaultModelConfigId(modelConfigId);
        return entity;
    }

    private LlmModelConfigDTO config(String backend) {
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend(backend);
        return config;
    }
}
