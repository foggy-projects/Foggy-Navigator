package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.TaskQueryProvider;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskDispatchFacadeTest {

    @Mock
    private UnifiedAgentResolver agentResolver;
    @Mock
    private SessionBindingService bindingService;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private A2aAgent agent;
    @Mock
    private TaskQueryProvider taskQueryProvider;
    @Mock
    private LlmModelManager llmModelManager;

    private TaskDispatchFacade facade;

    @BeforeEach
    void setUp() {
        facade = new TaskDispatchFacade(agentResolver, bindingService, sessionRepository,
                List.of(taskQueryProvider), llmModelManager);
    }

    @Test
    void createTask_prefersDirectoryLookupAndBindsResolvedAgentId() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .sessionId("session-1")
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("hi")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        when(agentResolver.resolveAgent(eq("dir-2"), any())).thenReturn(Optional.of(agent));
        when(agentResolver.getProviderType(eq("dir-2"), any())).thenReturn(Optional.of("claude-worker"));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder().id("agent-2").build());
        when(agent.sendTask(any())).thenReturn(A2aTask.builder()
                .id("task-1")
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build());

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("agent-2", result.getAgentId());
        verify(agentResolver).resolveAgent(eq("dir-2"), any());
        verify(agentResolver, never()).resolveAgent(eq("worker-1"), any());
        verify(bindingService).getOrBind("session-1", "agent-2", "claude-worker", "DIRECTORY_ID");
    }

    @Test
    void createTask_usesDirectProviderRouteWhenModelConfigTargetsCodex() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .directoryId("dir-2")
                .prompt("hi")
                .model("gpt-5.4")
                .modelConfigId("cfg-codex")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .requestSource("UI")
                .build();

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setWorkerBackend("OPENAI_CODEX");

        DispatchTaskDTO directTask = DispatchTaskDTO.builder()
                .taskId("task-codex-1")
                .providerType("codex-worker")
                .workerId("worker-1")
                .directoryId("dir-2")
                .build();

        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(modelConfig));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.createTaskDirect(any(), eq("user-1"), eq("tenant-1")))
                .thenReturn(directTask);

        DispatchTaskDTO result = facade.createTask(request, context);

        assertEquals("task-codex-1", result.getTaskId());
        verify(taskQueryProvider).createTaskDirect(any(), eq("user-1"), eq("tenant-1"));
        verifyNoInteractions(agentResolver, bindingService, agent);
    }

    @Test
    void listTasksPaged_aggregatesSessionsAcrossProviders() {
        TaskQueryProvider claudeProvider = mock(TaskQueryProvider.class);
        TaskQueryProvider codexProvider = mock(TaskQueryProvider.class);
        facade = new TaskDispatchFacade(agentResolver, bindingService, sessionRepository,
                List.of(claudeProvider, codexProvider), llmModelManager);

        DispatchTaskDTO claudeTask = DispatchTaskDTO.builder()
                .taskId("task-claude-1")
                .sessionId("session-claude-1")
                .workerId("worker-1")
                .createdAt(LocalDateTime.of(2026, 3, 24, 21, 0))
                .build();
        DispatchTaskDTO codexTask = DispatchTaskDTO.builder()
                .taskId("task-codex-1")
                .sessionId("session-codex-1")
                .workerId("worker-1")
                .createdAt(LocalDateTime.of(2026, 3, 24, 22, 0))
                .build();

        when(claudeProvider.listTasksPaged("user-1", 0, 20, null)).thenReturn(Map.of(
                "content", List.of(claudeTask),
                "totalSessions", 1L,
                "page", 0,
                "size", 20
        ));
        when(codexProvider.listTasksPaged("user-1", 0, 20, null)).thenReturn(Map.of(
                "content", List.of(codexTask),
                "totalSessions", 1L,
                "page", 0,
                "size", 20
        ));

        Object result = facade.listTasksPaged("user-1", 0, 20, null);

        Map<?, ?> page = assertInstanceOf(Map.class, result);
        assertEquals(2L, page.get("totalSessions"));
        List<?> content = assertInstanceOf(List.class, page.get("content"));
        assertEquals(2, content.size());
        assertEquals("task-codex-1", ((DispatchTaskDTO) content.get(0)).getTaskId());
        assertEquals("task-claude-1", ((DispatchTaskDTO) content.get(1)).getTaskId());
    }

    @Test
    void resumeTask_routesCodexThreadResumeToProvider() {
        TaskDispatchRequest request = TaskDispatchRequest.builder()
                .workerId("worker-1")
                .sessionId("session-1")
                .prompt("continue")
                .codexThreadId("thread-1")
                .build();
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("user-1")
                .tenantId("tenant-1")
                .sessionId("session-1")
                .requestSource("UI")
                .build();

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-codex-2")
                .sessionId("session-1")
                .codexThreadId("thread-1")
                .providerType("codex-worker")
                .build();

        when(agentResolver.getProviderType(eq("worker-1"), any())).thenReturn(Optional.of("codex-worker"));
        when(taskQueryProvider.getProviderType()).thenReturn("codex-worker");
        when(taskQueryProvider.resumeTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(resumedTask);

        DispatchTaskDTO result = facade.resumeTask(request, context);

        assertEquals("task-codex-2", result.getTaskId());
        assertEquals("thread-1", result.getCodexThreadId());
        verify(taskQueryProvider).resumeTask(eq("user-1"), eq("tenant-1"),
                argThat(params -> "thread-1".equals(params.get("codexThreadId"))
                        && "session-1".equals(params.get("sessionId"))
                        && "continue".equals(params.get("prompt"))));
    }
}
