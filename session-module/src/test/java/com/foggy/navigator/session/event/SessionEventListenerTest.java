package com.foggy.navigator.session.event;

import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.session.sse.UnifiedSseEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionEventListenerTest {

    @Mock
    private SessionManager sessionManager;
    @Mock
    private UnifiedSseEmitter sseEmitter;

    @Test
    void onAgentMessage_persistsOriginalSseMessageId() {
        SessionEventListener listener = new SessionEventListener(sessionManager, sseEmitter);
        AgentMessage agentMessage = AgentMessage.builder()
                .messageId("sse-message-1")
                .sessionId("session-1")
                .agentId("agent-1")
                .type(MessageType.TEXT_COMPLETE)
                .payload(Map.of("content", "assistant reply"))
                .build();

        listener.onAgentMessage(agentMessage);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(sessionManager).addMessage(eq("session-1"), messageCaptor.capture());
        assertEquals("sse-message-1", messageCaptor.getValue().getId(),
                "persisted DB message id must match the SSE message id used by the frontend");
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void sessionStartIsPushedButNotPersisted() {
        SessionEventListener listener = new SessionEventListener(sessionManager, sseEmitter);
        AgentMessage agentMessage = AgentMessage.builder()
                .messageId("sse-message-start")
                .sessionId("session-1")
                .agentId("agent-1")
                .type(MessageType.SESSION_START)
                .payload(Map.of("content", "Connecting to worker..."))
                .build();

        listener.handleMessage(agentMessage);

        verify(sessionManager, never()).addMessage(eq("session-1"), org.mockito.ArgumentMatchers.any());
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void sessionEndResultIsPersistedWhenItIsTheOnlyFinalAssistantText() {
        SessionEventListener listener = new SessionEventListener(sessionManager, sseEmitter);
        AgentMessage agentMessage = AgentMessage.builder()
                .messageId("sse-message-result")
                .sessionId("session-1")
                .agentId("codex-worker")
                .type(MessageType.SESSION_END)
                .payload(Map.of("content", "FINAL_STREAM_OK", "isResult", true))
                .build();

        listener.handleMessage(agentMessage);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(sessionManager).addMessage(eq("session-1"), messageCaptor.capture());
        assertEquals("FINAL_STREAM_OK", messageCaptor.getValue().getContent());
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void sessionEndResultIsNotPersistedWhenSameFinalTextAlreadyExists() {
        SessionEventListener listener = new SessionEventListener(sessionManager, sseEmitter);
        when(sessionManager.getRecentMessages("session-1", 50)).thenReturn(List.of(
                Message.user("session-1", "do it"),
                Message.assistant("session-1", "FINAL_STREAM_OK")));
        AgentMessage agentMessage = AgentMessage.builder()
                .messageId("sse-message-result")
                .sessionId("session-1")
                .agentId("codex-worker")
                .type(MessageType.SESSION_END)
                .payload(Map.of("content", "FINAL_STREAM_OK", "isResult", true))
                .build();

        listener.handleMessage(agentMessage);

        verify(sessionManager, never()).addMessage(eq("session-1"), org.mockito.ArgumentMatchers.any());
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void internalSystemStateSyncIsPushedButNotPersisted() {
        SessionEventListener listener = new SessionEventListener(sessionManager, sseEmitter);
        AgentMessage agentMessage = AgentMessage.builder()
                .messageId("sse-message-system")
                .sessionId("session-1")
                .agentId("agent-1")
                .type(MessageType.STATE_SYNC)
                .payload(Map.of("subtype", "system", "content", "worker started"))
                .build();

        listener.handleMessage(agentMessage);

        verify(sessionManager, never()).addMessage(eq("session-1"), org.mockito.ArgumentMatchers.any());
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void durableHandlingPropagatesPersistenceFailureAndDoesNotEmitSse() {
        SessionEventListener listener = new SessionEventListener(sessionManager, sseEmitter);
        AgentMessage agentMessage = AgentMessage.of(
                "session-1", "codex-worker", MessageType.TEXT_COMPLETE,
                Map.of("content", "assistant reply"));
        doThrow(new IllegalStateException("database unavailable"))
                .when(sessionManager).addMessage(eq("session-1"), org.mockito.ArgumentMatchers.any());

        assertThrows(SessionEventListener.MessagePersistenceException.class,
                () -> listener.handleMessageDurably(agentMessage));

        verify(sseEmitter, never()).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void durableHandlingIsNotRepeatedByAsyncEventEntryPoint() {
        SessionEventListener listener = new SessionEventListener(sessionManager, sseEmitter);
        AgentMessage agentMessage = AgentMessage.of(
                "session-1", "codex-worker", MessageType.TEXT_COMPLETE,
                Map.of("content", "assistant reply"));

        listener.handleMessageDurably(agentMessage);
        listener.onAgentMessage(agentMessage);

        verify(sessionManager, times(1))
                .addMessage(eq("session-1"), org.mockito.ArgumentMatchers.any());
        verify(sseEmitter, times(1)).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void nativeSubtaskUpdateIsPushedButNotPersistedAsChatHistory() {
        SessionEventListener listener = new SessionEventListener(sessionManager, sseEmitter);
        AgentMessage agentMessage = AgentMessage.builder()
                .messageId("native-subtask:task-1:12")
                .sessionId("session-1")
                .taskId("task-1")
                .agentId("codex-worker")
                .type(MessageType.NATIVE_SUBTASK_UPDATE)
                .payload(Map.of(
                        "taskId", "task-1",
                        "lastEventSeq", 12,
                        "subtask", Map.of("subtaskId", "child-1", "status", "running")))
                .build();

        listener.handleMessage(agentMessage);

        verify(sessionManager, never()).addMessage(eq("session-1"), org.mockito.ArgumentMatchers.any());
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }
}
