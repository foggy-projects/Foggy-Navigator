package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.exception.ContextAgentMismatchException;
import com.foggy.navigator.session.agent.ContextResolvingA2aAgent;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the ContextResolvingA2aAgent + ClaudeWorkerInnerA2aAgent pipeline.
 *
 * Most tests go through the full pipeline (ContextResolvingA2aAgent → ClaudeWorkerInnerA2aAgent)
 * to verify end-to-end behavior.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeWorkerA2aAgentTest {

    @Mock
    private ClaudeTaskService taskService;

    @Mock
    private AgentContextStore contextStore;

    private CodingAgentEntity entity;

    @BeforeEach
    void setUp() {
        entity = new CodingAgentEntity();
        entity.setAgentId("agent-1");
        entity.setUserId("user-1");
        entity.setTenantId("tenant-1");
        entity.setName("agent-one");
        entity.setAgentType("LOCAL_CLAUDE_WORKER");
        entity.setWorkerId("worker-1");
        entity.setDefaultDirectoryId("dir-default");
    }

    /** Full pipeline with context store */
    private A2aAgent pipelineWithContextStore() {
        InnerA2aAgent inner = new ClaudeWorkerInnerA2aAgent(entity, taskService, "D:\\default");
        return new ContextResolvingA2aAgent(inner, contextStore, entity);
    }

    /** Full pipeline without context store (null) */
    private A2aAgent pipelineWithoutContextStore() {
        InnerA2aAgent inner = new ClaudeWorkerInnerA2aAgent(entity, taskService, "D:\\default");
        return new ContextResolvingA2aAgent(inner, null, entity);
    }

    private A2aMessage simpleMessage(String prompt) {
        return A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text(prompt)))
                .metadata(Map.of("directoryId", "dir-1", "cwd", "D:\\work"))
                .build();
    }

    private TaskDTO defaultTaskDTO() {
        return TaskDTO.builder()
                .taskId("task-1")
                .sessionId("session-1")
                .workerId("worker-1")
                .directoryId("dir-1")
                .claudeSessionId("claude-sess-1")
                .status("RUNNING")
                .build();
    }

    // ===== Existing test =====

    @Test
    void sendTask_usesRequestedDirectoryAndCwd() {
        when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(TaskDTO.builder()
                .taskId("task-1")
                .sessionId("session-1")
                .workerId("worker-1")
                .directoryId("dir-requested")
                .build());

        A2aAgent agent = pipelineWithoutContextStore();
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("hi")))
                .metadata(Map.of(
                        "directoryId", "dir-requested",
                        "cwd", "D:\\requested"
                ))
                .build();

        agent.sendTask(message);

        ArgumentCaptor<CreateTaskForm> captor = ArgumentCaptor.forClass(CreateTaskForm.class);
        verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
        assertEquals("dir-requested", captor.getValue().getDirectoryId());
        assertEquals("D:\\requested", captor.getValue().getCwd());
    }

    // ===== P0: Dedup tests =====

    @Nested
    class Dedup {
        @Test
        void dedupHit_returnsExistingTask() {
            TaskDTO existing = TaskDTO.builder()
                    .taskId("existing-task")
                    .sessionId("existing-session")
                    .status("RUNNING")
                    .build();
            when(taskService.findRecentByDedupKey(anyString(), eq(60))).thenReturn(Optional.of(existing));

            A2aAgent agent = pipelineWithoutContextStore();
            A2aTask result = agent.sendTask(simpleMessage("hello"));

            assertEquals("existing-task", result.getId());
            verify(taskService, never()).createTask(anyString(), anyString(), any());
        }

        @Test
        void dedupMiss_createsNewTask() {
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(defaultTaskDTO());

            A2aAgent agent = pipelineWithoutContextStore();
            A2aTask result = agent.sendTask(simpleMessage("hello"));

            assertEquals("task-1", result.getId());
            assertEquals(A2aTaskState.SUBMITTED, result.getStatus().getState());
            verify(taskService).createTask(eq("user-1"), eq("tenant-1"), any());
            verify(taskService).setDedupKey(eq("task-1"), anyString());
        }

        @Test
        void dedupKey_deterministic_sameInputSameOutput() {
            // Two calls with same prompt should produce same dedup key → same findRecentByDedupKey arg
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            A2aAgent agent = pipelineWithoutContextStore();
            agent.sendTask(simpleMessage("same prompt"));

            ArgumentCaptor<String> key1 = ArgumentCaptor.forClass(String.class);
            verify(taskService).setDedupKey(anyString(), key1.capture());

            reset(taskService);
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            agent.sendTask(simpleMessage("same prompt"));

            ArgumentCaptor<String> key2 = ArgumentCaptor.forClass(String.class);
            verify(taskService).setDedupKey(anyString(), key2.capture());

            assertEquals(key1.getValue(), key2.getValue());
            assertEquals(32, key1.getValue().length()); // SHA-256 first 32 hex chars
        }
    }

    // ===== P0: Context store tests =====

    @Nested
    class ContextStoreTests {
        @Test
        void contextRestore_resumesWithClaudeSessionId() {
            when(contextStore.findSessionRefForAgent("ctx-1", "user-1", "agent-1", 24))
                    .thenReturn(Optional.of("claude-sess-existing"));
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            A2aAgent agent = pipelineWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("continue")))
                    .contextId("ctx-1")
                    .metadata(Map.of("directoryId", "dir-1"))
                    .build();

            agent.sendTask(message);

            // claudeSessionId 应被传递给 CreateTaskForm（通过 A2aContext.agentSessionRef）
            verify(contextStore).findSessionRefForAgent("ctx-1", "user-1", "agent-1", 24);
            ArgumentCaptor<CreateTaskForm> captor = ArgumentCaptor.forClass(CreateTaskForm.class);
            verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
            assertEquals("claude-sess-existing", captor.getValue().getClaudeSessionId(),
                    "contextStore 查到的 claudeSessionId 应通过 A2aContext 传给 CreateTaskForm");
        }

        @Test
        void contextRestore_noMatch_claudeSessionIdIsNull() {
            when(contextStore.findSessionRefForAgent("ctx-new", "user-1", "agent-1", 24))
                    .thenReturn(Optional.empty());
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            A2aAgent agent = pipelineWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("new task")))
                    .contextId("ctx-new")
                    .metadata(Map.of())
                    .build();

            agent.sendTask(message);

            ArgumentCaptor<CreateTaskForm> captor = ArgumentCaptor.forClass(CreateTaskForm.class);
            verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
            assertNull(captor.getValue().getClaudeSessionId(),
                    "contextStore 查不到时 claudeSessionId 应为 null");
        }

        @Test
        void noContextId_claudeSessionIdIsNull() {
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            A2aAgent agent = pipelineWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("no context")))
                    .metadata(Map.of())
                    .build();

            agent.sendTask(message);

            // 没有 contextId → 不查 findSessionRefForAgent → claudeSessionId 为 null
            verify(contextStore, never()).findSessionRefForAgent(anyString(), anyString(), anyString(), anyInt());
            ArgumentCaptor<CreateTaskForm> captor = ArgumentCaptor.forClass(CreateTaskForm.class);
            verify(taskService).createTask(anyString(), anyString(), captor.capture());
            assertNull(captor.getValue().getClaudeSessionId());
        }

        @Test
        void contextSave_afterCreation() {
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            A2aAgent agent = pipelineWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("do something")))
                    .contextId("ctx-2")
                    .metadata(Map.of())
                    .build();

            A2aTask result = agent.sendTask(message);

            // Decorator saves via saveSessionRefFull (not saveSessionRef)
            verify(contextStore).saveSessionRefFull(eq("ctx-2"), eq("claude-worker"),
                    eq("claude-sess-1"), eq("session-1"), eq("user-1"), eq("agent-1"), isNull());
            assertEquals("ctx-2", result.getContextId());
        }

        @Test
        void noContextStore_createsNormally() {
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            A2aAgent agent = pipelineWithoutContextStore();
            A2aTask result = agent.sendTask(simpleMessage("hello"));

            assertNotNull(result);
            assertEquals("task-1", result.getId());
            // No contextStore interaction
        }

        @Test
        void contextAlias_resolvesExisting() {
            AgentConversationContextEntity ctxEntity = new AgentConversationContextEntity();
            ctxEntity.setContextId("resolved-ctx-id");
            ctxEntity.setAgentSessionRef("claude-sess-alias");
            ctxEntity.setNavigatorSessionId("nav-sess-alias");
            ctxEntity.setContextAlias("my-alias");
            when(contextStore.findByAlias("my-alias", "user-1", "agent-1", 24))
                    .thenReturn(Optional.of(ctxEntity));
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            A2aAgent agent = pipelineWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("alias task")))
                    .contextAlias("my-alias")
                    .metadata(Map.of())
                    .build();

            agent.sendTask(message);

            ArgumentCaptor<CreateTaskForm> captor = ArgumentCaptor.forClass(CreateTaskForm.class);
            verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
            assertEquals("claude-sess-alias", captor.getValue().getClaudeSessionId(),
                    "Alias resolution should provide claudeSessionId");
            assertEquals("nav-sess-alias", captor.getValue().getSessionId(),
                    "Alias resolution should provide navigatorSessionId");
            assertEquals("resolved-ctx-id", captor.getValue().getContextId(),
                    "Alias resolution should provide resolved contextId");
        }

        @Test
        void contextMismatch_returnsFailedTask() {
            when(contextStore.findSessionRefForAgent("ctx-wrong", "user-1", "agent-1", 24))
                    .thenThrow(new ContextAgentMismatchException("ctx-wrong", "agent-other", "agent-1"));

            A2aAgent agent = pipelineWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("mismatched")))
                    .contextId("ctx-wrong")
                    .metadata(Map.of())
                    .build();

            A2aTask result = agent.sendTask(message);

            assertEquals(A2aTaskState.FAILED, result.getStatus().getState());
            assertTrue(result.getStatus().getDescription().contains("agent-other"));
            verify(taskService, never()).createTask(anyString(), anyString(), any());
        }
    }

    // ===== Null safety tests =====

    @Nested
    class NullSafety {
        @Test
        void nullMetadata_usesDefaults() {
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            A2aAgent agent = pipelineWithoutContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("hello")))
                    .metadata(null)
                    .build();

            A2aTask result = agent.sendTask(message);
            assertNotNull(result);

            // Should use defaults: defaultCwd and entity's defaultDirectoryId
            ArgumentCaptor<CreateTaskForm> captor = ArgumentCaptor.forClass(CreateTaskForm.class);
            verify(taskService).createTask(anyString(), anyString(), captor.capture());
            assertEquals("D:\\default", captor.getValue().getCwd());
            assertEquals("dir-default", captor.getValue().getDirectoryId());
        }
    }

    // ===== Task lifecycle tests =====

    @Nested
    class TaskLifecycle {
        @Test
        void getTask_found_mapsRunningToWorking() {
            TaskDTO dto = TaskDTO.builder()
                    .taskId("task-1").status("RUNNING").build();
            when(taskService.getTask("user-1", "task-1")).thenReturn(dto);

            A2aAgent agent = pipelineWithoutContextStore();
            Optional<A2aTask> result = agent.getTask("task-1");

            assertTrue(result.isPresent());
            assertEquals(A2aTaskState.WORKING, result.get().getStatus().getState());
        }

        @Test
        void getTask_completed_includesArtifacts() {
            TaskDTO dto = TaskDTO.builder()
                    .taskId("task-1").status("COMPLETED").resultText("done!").build();
            when(taskService.getTask("user-1", "task-1")).thenReturn(dto);

            A2aAgent agent = pipelineWithoutContextStore();
            Optional<A2aTask> result = agent.getTask("task-1");

            assertTrue(result.isPresent());
            assertEquals(A2aTaskState.COMPLETED, result.get().getStatus().getState());
            assertNotNull(result.get().getArtifacts());
            assertEquals(1, result.get().getArtifacts().size());
            assertEquals("done!", result.get().getArtifacts().get(0).getParts().get(0).getText());
        }

        @Test
        void getTask_failed_includesErrorDescription() {
            TaskDTO dto = TaskDTO.builder()
                    .taskId("task-1").status("FAILED").errorMessage("boom").build();
            when(taskService.getTask("user-1", "task-1")).thenReturn(dto);

            A2aAgent agent = pipelineWithoutContextStore();
            Optional<A2aTask> result = agent.getTask("task-1");

            assertTrue(result.isPresent());
            assertEquals(A2aTaskState.FAILED, result.get().getStatus().getState());
            assertEquals("boom", result.get().getStatus().getDescription());
        }

        @Test
        void getTask_notFound_returnsEmpty() {
            when(taskService.getTask("user-1", "nonexistent"))
                    .thenThrow(new IllegalArgumentException("not found"));

            A2aAgent agent = pipelineWithoutContextStore();
            Optional<A2aTask> result = agent.getTask("nonexistent");

            assertTrue(result.isEmpty());
        }

        @Test
        void cancelTask_delegatesToService() {
            A2aAgent agent = pipelineWithoutContextStore();
            agent.cancelTask("task-1");

            verify(taskService).abortTask("task-1");
        }

        @Test
        void getAgentCard_returnsCorrectFields() {
            A2aAgent agent = pipelineWithoutContextStore();
            A2aAgentCard card = agent.getAgentCard();

            assertEquals("agent-1", card.getId());
            assertEquals("agent-one", card.getName());
            assertEquals(1, card.getSkills().size());
            assertEquals("coding", card.getSkills().get(0).getId());
        }
    }
}
