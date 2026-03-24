package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.spi.agent.AgentContextStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodexWorkerA2aAgentTest {

    @Mock
    private CodexTaskService taskService;

    @Mock
    private AgentContextStore contextStore;

    @Test
    void sendTask_usesRequestedDirectoryCwdAndModelConfig() {
        CodingAgentEntity entity = defaultEntity();

        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                .taskId("task-1")
                .sessionId("session-1")
                .workerId("worker-1")
                .directoryId("dir-requested")
                .build());

        CodexWorkerA2aAgent agent = new CodexWorkerA2aAgent(entity, taskService, "D:\\default", null);
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("hi")))
                .metadata(Map.of(
                        "directoryId", "dir-requested",
                        "cwd", "D:\\requested",
                        "modelConfigId", "model-config-1"
                ))
                .build();

        agent.sendTask(message);

        ArgumentCaptor<CreateCodexTaskForm> captor = ArgumentCaptor.forClass(CreateCodexTaskForm.class);
        verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
        assertEquals("dir-requested", captor.getValue().getDirectoryId());
        assertEquals("D:\\requested", captor.getValue().getCwd());
        assertEquals("model-config-1", captor.getValue().getModelConfigId());
    }

    // ── New tests ──

    @Test
    void sendTask_contextRestore_resumesWithCodexThreadId() {
        CodingAgentEntity entity = defaultEntity();

        when(contextStore.findSessionRef("ctx-1", "user-1", 24))
                .thenReturn(Optional.of("thread-existing"));

        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                .taskId("task-2")
                .sessionId("session-2")
                .workerId("worker-1")
                .codexThreadId("thread-existing")
                .build());

        CodexWorkerA2aAgent agent = new CodexWorkerA2aAgent(entity, taskService, "D:\\default", contextStore);
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("continue")))
                .contextId("ctx-1")
                .build();

        A2aTask result = agent.sendTask(message);

        ArgumentCaptor<CreateCodexTaskForm> captor = ArgumentCaptor.forClass(CreateCodexTaskForm.class);
        verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
        assertEquals("thread-existing", captor.getValue().getCodexThreadId());
        assertEquals("ctx-1", result.getContextId());
    }

    @Test
    void sendTask_savesContextMapping_afterCreation() {
        CodingAgentEntity entity = defaultEntity();

        when(contextStore.findSessionRef("ctx-2", "user-1", 24))
                .thenReturn(Optional.of("thread-saved"));

        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                .taskId("task-3")
                .sessionId("session-3")
                .workerId("worker-1")
                .codexThreadId("thread-saved")
                .build());

        CodexWorkerA2aAgent agent = new CodexWorkerA2aAgent(entity, taskService, "D:\\default", contextStore);
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("do something")))
                .contextId("ctx-2")
                .build();

        agent.sendTask(message);

        verify(contextStore).saveSessionRef("ctx-2", "codex-worker", "thread-saved", "user-1", "agent-1");
    }

    @Test
    void sendTask_nullMetadata_usesDefaults() {
        CodingAgentEntity entity = defaultEntity();

        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                .taskId("task-4")
                .sessionId("session-4")
                .workerId("worker-1")
                .directoryId("dir-default")
                .build());

        CodexWorkerA2aAgent agent = new CodexWorkerA2aAgent(entity, taskService, "D:\\default", null);
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("hello")))
                .metadata(null)
                .build();

        A2aTask result = agent.sendTask(message);

        ArgumentCaptor<CreateCodexTaskForm> captor = ArgumentCaptor.forClass(CreateCodexTaskForm.class);
        verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
        assertEquals("D:\\default", captor.getValue().getCwd());
        assertEquals("dir-default", captor.getValue().getDirectoryId());
        assertNotNull(result);
        assertEquals(A2aTaskState.SUBMITTED, result.getStatus().getState());
    }

    @Test
    void sendTask_noContextStore_createsNormally() {
        CodingAgentEntity entity = defaultEntity();

        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                .taskId("task-5")
                .sessionId("session-5")
                .workerId("worker-1")
                .build());

        CodexWorkerA2aAgent agent = new CodexWorkerA2aAgent(entity, taskService, "D:\\default", null);
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("work")))
                .contextId("ctx-no-store")
                .build();

        A2aTask result = agent.sendTask(message);

        assertNotNull(result);
        assertEquals("task-5", result.getId());
        // No contextStore interaction
        verifyNoInteractions(contextStore);
    }

    @Test
    void getTask_found_mapsState() {
        CodingAgentEntity entity = defaultEntity();

        CodexTaskDTO dto = CodexTaskDTO.builder()
                .taskId("task-6")
                .sessionId("session-6")
                .workerId("worker-1")
                .status("COMPLETED")
                .resultText("Done!")
                .build();
        when(taskService.getTask("user-1", "task-6")).thenReturn(dto);

        CodexWorkerA2aAgent agent = new CodexWorkerA2aAgent(entity, taskService, "D:\\default", null);

        Optional<A2aTask> result = agent.getTask("task-6");

        assertTrue(result.isPresent());
        assertEquals("task-6", result.get().getId());
        assertEquals(A2aTaskState.COMPLETED, result.get().getStatus().getState());
        assertNotNull(result.get().getArtifacts());
        assertFalse(result.get().getArtifacts().isEmpty());
    }

    @Test
    void isAvailable_returnsTrue() {
        CodingAgentEntity entity = defaultEntity();
        CodexWorkerA2aAgent agent = new CodexWorkerA2aAgent(entity, taskService, "D:\\default", null);

        assertTrue(agent.isAvailable());
    }

    // ---- helpers ----

    private CodingAgentEntity defaultEntity() {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("agent-1");
        entity.setUserId("user-1");
        entity.setTenantId("tenant-1");
        entity.setName("codex-agent");
        entity.setAgentType("LOCAL_CODEX_WORKER");
        entity.setWorkerId("worker-1");
        entity.setDefaultDirectoryId("dir-default");
        return entity;
    }
}
