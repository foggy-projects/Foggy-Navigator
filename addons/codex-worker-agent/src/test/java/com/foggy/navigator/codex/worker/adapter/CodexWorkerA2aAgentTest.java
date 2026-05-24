package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.model.dto.CodexTaskDTO;
import com.foggy.navigator.codex.worker.model.form.CreateCodexTaskForm;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Tests for the ContextResolvingA2aAgent + CodexWorkerInnerA2aAgent pipeline.
 */
@ExtendWith(MockitoExtension.class)
class CodexWorkerA2aAgentTest {

    @Mock
    private CodexTaskService taskService;

    @Mock
    private AgentContextStore contextStore;

    private CodingAgentEntity entity;

    @BeforeEach
    void setUp() {
        entity = new CodingAgentEntity();
        entity.setAgentId("agent-1");
        entity.setUserId("user-1");
        entity.setTenantId("tenant-1");
        entity.setName("codex-agent");
        entity.setAgentType("LOCAL_CODEX_WORKER");
        entity.setWorkerId("worker-1");
        entity.setDefaultDirectoryId("dir-default");
    }

    /** Full pipeline with context store */
    private A2aAgent pipelineWithContextStore() {
        InnerA2aAgent inner = new CodexWorkerInnerA2aAgent(entity, taskService, "D:\\default");
        return new ContextResolvingA2aAgent(inner, contextStore, entity);
    }

    /** Full pipeline without context store (null) */
    private A2aAgent pipelineWithoutContextStore() {
        InnerA2aAgent inner = new CodexWorkerInnerA2aAgent(entity, taskService, "D:\\default");
        return new ContextResolvingA2aAgent(inner, null, entity);
    }

    private A2aMessage simpleMessage(String prompt) {
        return A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text(prompt)))
                .metadata(Map.of("directoryId", "dir-1", "cwd", "D:\\work"))
                .build();
    }

    @Test
    void sendTask_usesRequestedDirectoryCwdAndModelConfig() {
        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                .taskId("task-1")
                .sessionId("session-1")
                .workerId("worker-effective")
                .directoryId("dir-requested")
                .build());

        A2aAgent agent = pipelineWithoutContextStore();
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("hi")))
                .metadata(Map.of(
                        "workerId", "worker-effective",
                        "directoryId", "dir-requested",
                        "cwd", "D:\\requested",
                        "modelConfigId", "model-config-1"
                ))
                .build();

        agent.sendTask(message);

        ArgumentCaptor<CreateCodexTaskForm> captor = ArgumentCaptor.forClass(CreateCodexTaskForm.class);
        verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
        assertEquals("agent-1", captor.getValue().getAgentId());
        assertEquals("worker-effective", captor.getValue().getWorkerId());
        assertEquals("dir-requested", captor.getValue().getDirectoryId());
        assertEquals("D:\\requested", captor.getValue().getCwd());
        assertEquals("model-config-1", captor.getValue().getModelConfigId());
    }

    @Test
    void sendTask_forwardsImagesMetadata() {
        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                .taskId("task-images")
                .sessionId("session-images")
                .workerId("worker-1")
                .build());

        A2aAgent agent = pipelineWithoutContextStore();
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("describe image")))
                .metadata(Map.of(
                        "images", List.of("[{\"name\":\"screen.png\",\"data\":\"YmFzZTY0\",\"mime_type\":\"image/png\"}]")
                ))
                .build();

        agent.sendTask(message);

        ArgumentCaptor<CreateCodexTaskForm> captor = ArgumentCaptor.forClass(CreateCodexTaskForm.class);
        verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
        assertEquals("[{\"name\":\"screen.png\",\"data\":\"YmFzZTY0\",\"mime_type\":\"image/png\"}]",
                captor.getValue().getImages());
    }

    @Test
    void sendTask_rejectsUnsupportedSystemPrompt() {
        A2aAgent agent = pipelineWithoutContextStore();
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("hello")))
                .metadata(Map.of("systemPrompt", "You are strict"))
                .build();

        A2aTask result = agent.sendTask(message);

        assertEquals(A2aTaskState.FAILED, result.getStatus().getState());
        assertTrue(result.getStatus().getDescription().contains("firstMsg"));
        verify(taskService, never()).createTask(anyString(), anyString(), any());
    }

    @Test
    void sendTask_appliesFirstMsgOnFirstTurn() {
        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                .taskId("task-first-msg")
                .sessionId("session-first-msg")
                .workerId("worker-1")
                .build());

        A2aAgent agent = pipelineWithoutContextStore();
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("implement feature")))
                .metadata(Map.of("firstMsg", "Project context"))
                .build();

        agent.sendTask(message);

        ArgumentCaptor<CreateCodexTaskForm> captor = ArgumentCaptor.forClass(CreateCodexTaskForm.class);
        verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
        assertTrue(captor.getValue().getPrompt().contains("[Initial Message]"));
        assertTrue(captor.getValue().getPrompt().contains("Project context"));
        assertTrue(captor.getValue().getPrompt().contains("[User Message]"));
    }

    // ===== Context store tests =====

    @Nested
    class ContextStoreTests {
        @Test
        void contextRestore_resumesWithCodexThreadId() {
            AgentConversationContextEntity ctxEntity = new AgentConversationContextEntity();
            ctxEntity.setContextId("ctx-1");
            ctxEntity.setAgentSessionRef("thread-existing");
            ctxEntity.setNavigatorSessionId("nav-sess-existing");
            when(contextStore.findContextForAgent("ctx-1", "user-1", "agent-1"))
                    .thenReturn(Optional.of(ctxEntity));
            when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                    .taskId("task-2")
                    .sessionId("session-2")
                    .workerId("worker-1")
                    .codexThreadId("thread-existing")
                    .build());

            A2aAgent agent = pipelineWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("continue")))
                    .contextId("ctx-1")
                    .build();

            A2aTask result = agent.sendTask(message);

            verify(contextStore).findContextForAgent("ctx-1", "user-1", "agent-1");
            ArgumentCaptor<CreateCodexTaskForm> captor = ArgumentCaptor.forClass(CreateCodexTaskForm.class);
            verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
            assertEquals("thread-existing", captor.getValue().getCodexThreadId(),
                    "contextStore 查到的 codexThreadId 应通过 A2aContext 传给 CreateCodexTaskForm");
            assertEquals("nav-sess-existing", captor.getValue().getSessionId());
            assertEquals("ctx-1", result.getContextId());
        }

        @Test
        void contextSave_afterCreation() {
            when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                    .taskId("task-3")
                    .sessionId("session-3")
                    .workerId("worker-1")
                    .codexThreadId("thread-saved")
                    .build());

            A2aAgent agent = pipelineWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("do something")))
                    .contextId("ctx-2")
                    .metadata(Map.of())
                    .build();

            A2aTask result = agent.sendTask(message);

            // Decorator saves via saveSessionRefFull with codexThreadId
            verify(contextStore).saveSessionRefFull(eq("ctx-2"), eq("codex-worker"),
                    eq("thread-saved"), eq("session-3"), eq("user-1"), eq("agent-1"), isNull());
            assertEquals("ctx-2", result.getContextId());
        }

        @Test
        void contextAlias_resolvesExisting() {
            AgentConversationContextEntity ctxEntity = new AgentConversationContextEntity();
            ctxEntity.setContextId("resolved-ctx-id");
            ctxEntity.setAgentSessionRef("thread-alias");
            ctxEntity.setNavigatorSessionId("nav-sess-alias");
            ctxEntity.setContextAlias("my-alias");
            when(contextStore.findByAlias("my-alias", "user-1", "agent-1"))
                    .thenReturn(Optional.of(ctxEntity));
            when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                    .taskId("task-alias")
                    .sessionId("session-alias")
                    .workerId("worker-1")
                    .codexThreadId("thread-alias")
                    .build());

            A2aAgent agent = pipelineWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("alias task")))
                    .contextAlias("my-alias")
                    .metadata(Map.of())
                    .build();

            agent.sendTask(message);

            ArgumentCaptor<CreateCodexTaskForm> captor = ArgumentCaptor.forClass(CreateCodexTaskForm.class);
            verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
            assertEquals("thread-alias", captor.getValue().getCodexThreadId(),
                    "Alias resolution should provide codexThreadId");
            assertEquals("nav-sess-alias", captor.getValue().getSessionId(),
                    "Alias resolution should provide navigatorSessionId");
        }

        @Test
        void firstMsg_isIgnoredOnResume() {
            AgentConversationContextEntity ctxEntity = new AgentConversationContextEntity();
            ctxEntity.setContextId("ctx-continue");
            ctxEntity.setAgentSessionRef("thread-existing");
            ctxEntity.setNavigatorSessionId("nav-sess-existing");
            when(contextStore.findContextForAgent("ctx-continue", "user-1", "agent-1"))
                    .thenReturn(Optional.of(ctxEntity));
            when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                    .taskId("task-continue")
                    .sessionId("session-continue")
                    .workerId("worker-1")
                    .codexThreadId("thread-existing")
                    .build());

            A2aAgent agent = pipelineWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("continue work")))
                    .contextId("ctx-continue")
                    .metadata(Map.of("firstMsg", "Should not reapply"))
                    .build();

            agent.sendTask(message);

            ArgumentCaptor<CreateCodexTaskForm> captor = ArgumentCaptor.forClass(CreateCodexTaskForm.class);
            verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
            assertEquals("continue work", captor.getValue().getPrompt());
        }

        @Test
        void firstMsg_isIgnoredWhenNavigatorSessionExistsEvenIfAgentSessionMissing() {
            AgentConversationContextEntity ctxEntity = new AgentConversationContextEntity();
            ctxEntity.setContextId("ctx-nav-only");
            ctxEntity.setNavigatorSessionId("nav-sess-existing");
            when(contextStore.findContextForAgent("ctx-nav-only", "user-1", "agent-1"))
                    .thenReturn(Optional.of(ctxEntity));
            when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                    .taskId("task-nav-only")
                    .sessionId("session-nav-only")
                    .workerId("worker-1")
                    .build());

            A2aAgent agent = pipelineWithContextStore();
            A2aMessage message = A2aMessage.builder()
                    .role("user")
                    .parts(List.of(A2aPart.text("continue work")))
                    .contextId("ctx-nav-only")
                    .metadata(Map.of("firstMsg", "Should not reapply"))
                    .build();

            agent.sendTask(message);

            ArgumentCaptor<CreateCodexTaskForm> captor = ArgumentCaptor.forClass(CreateCodexTaskForm.class);
            verify(taskService).createTask(eq("user-1"), eq("tenant-1"), captor.capture());
            assertEquals("continue work", captor.getValue().getPrompt());
            assertEquals("nav-sess-existing", captor.getValue().getSessionId());
        }
    }

    // ===== Null safety tests =====

    @Test
    void sendTask_nullMetadata_usesDefaults() {
        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                .taskId("task-4")
                .sessionId("session-4")
                .workerId("worker-1")
                .directoryId("dir-default")
                .build());

        A2aAgent agent = pipelineWithoutContextStore();
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
        when(taskService.createTask(eq("user-1"), eq("tenant-1"), any())).thenReturn(CodexTaskDTO.builder()
                .taskId("task-5")
                .sessionId("session-5")
                .workerId("worker-1")
                .build());

        A2aAgent agent = pipelineWithoutContextStore();
        A2aMessage message = A2aMessage.builder()
                .role("user")
                .parts(List.of(A2aPart.text("work")))
                .contextId("ctx-no-store")
                .build();

        A2aTask result = agent.sendTask(message);

        assertNotNull(result);
        assertEquals("task-5", result.getId());
    }

    // ===== Task lifecycle tests =====

    @Nested
    class TaskLifecycle {
        @Test
        void getTask_found_mapsState() {
            CodexTaskDTO dto = CodexTaskDTO.builder()
                    .taskId("task-6")
                    .sessionId("session-6")
                    .workerId("worker-1")
                    .status("COMPLETED")
                    .resultText("Done!")
                    .build();
            when(taskService.getTask("user-1", "task-6")).thenReturn(dto);

            A2aAgent agent = pipelineWithoutContextStore();
            Optional<A2aTask> result = agent.getTask("task-6");

            assertTrue(result.isPresent());
            assertEquals("task-6", result.get().getId());
            assertEquals(A2aTaskState.COMPLETED, result.get().getStatus().getState());
            assertNotNull(result.get().getArtifacts());
            assertFalse(result.get().getArtifacts().isEmpty());
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
            assertEquals("codex-agent", card.getName());
            assertFalse(Boolean.TRUE.equals(card.getCapabilities().getSupportsSystemPrompt()));
        }
    }
}
