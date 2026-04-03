package com.foggy.navigator.session.controller;

import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.session.registry.DefaultA2aAgentRegistry;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.session.service.SharingKeyService;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SharedTaskControllerTest {

    @Mock
    private SharingKeyService sharingKeyService;
    @Mock
    private DefaultA2aAgentRegistry registry;
    @Mock
    private TaskDispatchFacade taskDispatchFacade;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private A2aAgent agent;

    @Test
    void getTask_returnsA2aTaskWhenSharingKeyMatchesTaskAgent() {
        SharedTaskController controller = new SharedTaskController(
                sharingKeyService, registry, taskDispatchFacade, sessionRepository, sessionManager);
        SharingKeyEntity keyEntity = buildSharingKey("agent-1", "owner-1");
        DispatchTaskDTO dispatchTask = DispatchTaskDTO.builder()
                .taskId("task-1")
                .agentId("agent-1")
                .build();
        A2aTask a2aTask = A2aTask.builder().id("task-1").build();

        when(sharingKeyService.validateForKeyOnly("shk-1")).thenReturn(keyEntity);
        when(taskDispatchFacade.getTask(eq("task-1"), any())).thenReturn(Optional.of(dispatchTask));
        when(registry.resolveAgent("agent-1", "owner-1")).thenReturn(Optional.of(agent));
        when(agent.getTask("task-1")).thenReturn(Optional.of(a2aTask));

        RX<A2aTask> result = controller.getTask("shk-1", "task-1");

        assertNotNull(result.getData());
        assertEquals("task-1", result.getData().getId());
    }

    @Test
    void getTask_returnsFailWhenTaskAgentDoesNotMatchSharingKey() {
        SharedTaskController controller = new SharedTaskController(
                sharingKeyService, registry, taskDispatchFacade, sessionRepository, sessionManager);
        SharingKeyEntity keyEntity = buildSharingKey("agent-1", "owner-1");
        DispatchTaskDTO dispatchTask = DispatchTaskDTO.builder()
                .taskId("task-1")
                .agentId("agent-2")
                .build();

        when(sharingKeyService.validateForKeyOnly("shk-1")).thenReturn(keyEntity);
        when(taskDispatchFacade.getTask(eq("task-1"), any())).thenReturn(Optional.of(dispatchTask));

        RX<A2aTask> result = controller.getTask("shk-1", "task-1");

        assertNull(result.getData());
        verify(registry, never()).resolveAgent(anyString(), anyString());
    }

    @Test
    void cancelTask_usesFacadeWhenTaskIsAuthorized() {
        SharedTaskController controller = new SharedTaskController(
                sharingKeyService, registry, taskDispatchFacade, sessionRepository, sessionManager);
        SharingKeyEntity keyEntity = buildSharingKey("agent-1", "owner-1");
        DispatchTaskDTO dispatchTask = DispatchTaskDTO.builder()
                .taskId("task-1")
                .agentId("agent-1")
                .build();

        when(sharingKeyService.validateForKeyOnly("shk-1")).thenReturn(keyEntity);
        when(taskDispatchFacade.getTask(eq("task-1"), any())).thenReturn(Optional.of(dispatchTask));

        RX<String> result = controller.cancelTask("shk-1", "task-1");

        assertEquals("Task cancelled", result.getData());
        verify(taskDispatchFacade).cancelTask(eq("task-1"), eq("agent-1"), any());
    }

    @Test
    void getSessionMessages_returnsConversationWhenSessionBelongsToSharedAgent() {
        SharedTaskController controller = new SharedTaskController(
                sharingKeyService, registry, taskDispatchFacade, sessionRepository, sessionManager);
        SharingKeyEntity keyEntity = buildSharingKey("agent-1", "owner-1");
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("owner-1");
        session.setAgentId("agent-1");
        List<Message> messages = List.of(Message.builder()
                .id("msg-1")
                .sessionId("session-1")
                .role(MessageRole.USER)
                .content("hello")
                .createdAt(LocalDateTime.now())
                .build());

        when(sharingKeyService.validateForKeyOnly("shk-1")).thenReturn(keyEntity);
        when(sessionRepository.findByIdAndUserId("session-1", "owner-1")).thenReturn(Optional.of(session));
        when(sessionManager.getAllMessages("session-1")).thenReturn(messages);

        RX<List<Message>> result = controller.getSessionMessages("shk-1", "session-1");

        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        assertEquals("hello", result.getData().get(0).getContent());
    }

    @Test
    void getSessionMessages_returnsFailWhenSessionAgentDoesNotMatchSharingKey() {
        SharedTaskController controller = new SharedTaskController(
                sharingKeyService, registry, taskDispatchFacade, sessionRepository, sessionManager);
        SharingKeyEntity keyEntity = buildSharingKey("agent-1", "owner-1");
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("owner-1");
        session.setAgentId("agent-2");

        when(sharingKeyService.validateForKeyOnly("shk-1")).thenReturn(keyEntity);
        when(sessionRepository.findByIdAndUserId("session-1", "owner-1")).thenReturn(Optional.of(session));

        RX<List<Message>> result = controller.getSessionMessages("shk-1", "session-1");

        assertNull(result.getData());
        verify(sessionManager, never()).getAllMessages(anyString());
    }

    private SharingKeyEntity buildSharingKey(String agentId, String ownerUserId) {
        SharingKeyEntity entity = new SharingKeyEntity();
        entity.setSharingKey("shk-1");
        entity.setAgentId(agentId);
        entity.setOwnerUserId(ownerUserId);
        entity.setEnabled(true);
        return entity;
    }
}
