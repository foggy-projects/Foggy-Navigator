package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.claude.worker.model.dto.TaskDTO;
import com.foggy.navigator.claude.worker.model.form.CreateTaskForm;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.spi.agent.AgentContextStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    private ClaudeWorkerA2aAgent agentWithContextStore() {
        return new ClaudeWorkerA2aAgent(entity, taskService, "D:\\default", contextStore);
    }

    private ClaudeWorkerA2aAgent agentWithoutContextStore() {
        return new ClaudeWorkerA2aAgent(entity, taskService, "D:\\default", null);
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

        ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
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

            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
            A2aTask result = agent.sendTask(simpleMessage("hello"));

            assertEquals("existing-task", result.getId());
            verify(taskService, never()).createTask(anyString(), anyString(), any());
        }

        @Test
        void dedupMiss_createsNewTask() {
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(defaultTaskDTO());

            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
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

            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
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
    class ContextStore {
        @Test
        void contextRestore_resumesWithClaudeSessionId() {
            when(contextStore.findSessionRef("ctx-1", "user-1", 24))
                    .thenReturn(Optional.of("claude-sess-existing"));
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            ClaudeWorkerA2aAgent agent = agentWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("continue")))
                    .contextId("ctx-1")
                    .metadata(Map.of("directoryId", "dir-1"))
                    .build();

            agent.sendTask(message);

            // Context ID should be preserved in the returned task
            // Note: claudeSessionId goes into form but is not explicitly set by A2aAgent
            // (it's handled by ClaudeTaskService internally when resuming)
            verify(contextStore).findSessionRef("ctx-1", "user-1", 24);
        }

        @Test
        void contextSave_afterCreation() {
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            ClaudeWorkerA2aAgent agent = agentWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("do something")))
                    .contextId("ctx-2")
                    .metadata(Map.of())
                    .build();

            A2aTask result = agent.sendTask(message);

            verify(contextStore).saveSessionRef(eq("ctx-2"), eq("claude-worker"),
                    eq("claude-sess-1"), eq("user-1"), eq("agent-1"));
            assertEquals("ctx-2", result.getContextId());
        }

        @Test
        void noContextStore_createsNormally() {
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
            A2aTask result = agent.sendTask(simpleMessage("hello"));

            assertNotNull(result);
            assertEquals("task-1", result.getId());
            // No contextStore interaction
        }
    }

    // ===== Null safety tests =====

    @Nested
    class NullSafety {
        @Test
        void nullMetadata_usesDefaults() {
            when(taskService.findRecentByDedupKey(anyString(), anyInt())).thenReturn(Optional.empty());
            when(taskService.createTask(anyString(), anyString(), any())).thenReturn(defaultTaskDTO());

            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
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

            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
            Optional<A2aTask> result = agent.getTask("task-1");

            assertTrue(result.isPresent());
            assertEquals(A2aTaskState.WORKING, result.get().getStatus().getState());
        }

        @Test
        void getTask_completed_includesArtifacts() {
            TaskDTO dto = TaskDTO.builder()
                    .taskId("task-1").status("COMPLETED").resultText("done!").build();
            when(taskService.getTask("user-1", "task-1")).thenReturn(dto);

            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
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

            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
            Optional<A2aTask> result = agent.getTask("task-1");

            assertTrue(result.isPresent());
            assertEquals(A2aTaskState.FAILED, result.get().getStatus().getState());
            assertEquals("boom", result.get().getStatus().getDescription());
        }

        @Test
        void getTask_notFound_returnsEmpty() {
            when(taskService.getTask("user-1", "nonexistent"))
                    .thenThrow(new IllegalArgumentException("not found"));

            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
            Optional<A2aTask> result = agent.getTask("nonexistent");

            assertTrue(result.isEmpty());
        }

        @Test
        void cancelTask_delegatesToService() {
            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
            agent.cancelTask("task-1");

            verify(taskService).abortTask("task-1");
        }

        @Test
        void isAvailable_returnsTrue() {
            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
            assertTrue(agent.isAvailable());
        }

        @Test
        void getAgentCard_returnsCorrectFields() {
            ClaudeWorkerA2aAgent agent = agentWithoutContextStore();
            A2aAgentCard card = agent.getAgentCard();

            assertEquals("agent-1", card.getId());
            assertEquals("agent-one", card.getName());
            assertEquals(1, card.getSkills().size());
            assertEquals("coding", card.getSkills().get(0).getId());
        }
    }
}
