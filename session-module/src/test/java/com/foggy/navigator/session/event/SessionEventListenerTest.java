package com.foggy.navigator.session.event;

import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.session.service.SessionMessageDurablePersistenceCoordinator;
import com.foggy.navigator.session.sse.UnifiedSseEmitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    @Mock
    private SessionMessageDurablePersistenceCoordinator messagePersistenceCoordinator;

    @Test
    void onAgentMessage_persistsOriginalSseMessageId() {
        SessionEventListener listener = new SessionEventListener(
                sessionManager, sseEmitter, messagePersistenceCoordinator);
        AgentMessage agentMessage = AgentMessage.builder()
                .messageId("sse-message-1")
                .sessionId("session-1")
                .agentId("agent-1")
                .type(MessageType.TEXT_COMPLETE)
                .payload(Map.of("content", "assistant reply"))
                .build();

        listener.onAgentMessage(agentMessage);

        verify(messagePersistenceCoordinator).persist(agentMessage);
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void storageKeysAreRedactedBeforePersistenceAndSseEmission() {
        SessionEventListener listener = new SessionEventListener(
                sessionManager, sseEmitter, messagePersistenceCoordinator);
        AgentMessage agentMessage = AgentMessage.builder()
                .messageId("sse-message-storage-key")
                .sessionId("session-1")
                .agentId("agent-1")
                .type(MessageType.TOOL_CALL_RESULT)
                .payload(Map.of(
                        "storageKey", "root-secret.gz",
                        "nested", Map.of("storage_key", "nested-secret.gz", "visible", "yes"),
                        "items", List.of(Map.of("storageKey", "list-secret.gz", "visible", "item")),
                        "data", "bounded preview"))
                .build();

        listener.handleMessage(agentMessage);

        ArgumentCaptor<AgentMessage> persisted = ArgumentCaptor.forClass(AgentMessage.class);
        ArgumentCaptor<AgentMessage> emitted = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messagePersistenceCoordinator).persist(persisted.capture());
        verify(sseEmitter).sendSessionEvent(eq("session-1"), emitted.capture());
        Map<?, ?> publicPayload = (Map<?, ?>) emitted.getValue().getPayload();
        assertFalse(publicPayload.containsKey("storageKey"));
        assertFalse(((Map<?, ?>) publicPayload.get("nested")).containsKey("storage_key"));
        assertFalse(((Map<?, ?>) ((List<?>) publicPayload.get("items")).get(0)).containsKey("storageKey"));
        assertEquals(publicPayload, persisted.getValue().getPayload());
    }

    @Test
    void sessionStartIsPushedButNotPersisted() {
        SessionEventListener listener = new SessionEventListener(
                sessionManager, sseEmitter, messagePersistenceCoordinator);
        AgentMessage agentMessage = AgentMessage.builder()
                .messageId("sse-message-start")
                .sessionId("session-1")
                .agentId("agent-1")
                .type(MessageType.SESSION_START)
                .payload(Map.of("content", "Connecting to worker..."))
                .build();

        listener.handleMessage(agentMessage);

        verify(messagePersistenceCoordinator, never()).persist(agentMessage);
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void sessionEndResultIsPersistedWhenItIsTheOnlyFinalAssistantText() {
        SessionEventListener listener = new SessionEventListener(
                sessionManager, sseEmitter, messagePersistenceCoordinator);
        AgentMessage agentMessage = AgentMessage.builder()
                .messageId("sse-message-result")
                .sessionId("session-1")
                .agentId("codex-worker")
                .type(MessageType.SESSION_END)
                .payload(Map.of("content", "FINAL_STREAM_OK", "isResult", true))
                .build();

        listener.handleMessage(agentMessage);

        verify(messagePersistenceCoordinator).persist(agentMessage);
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void sessionEndResultIsNotPersistedWhenSameFinalTextAlreadyExists() {
        SessionEventListener listener = new SessionEventListener(
                sessionManager, sseEmitter, messagePersistenceCoordinator);
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

        verify(messagePersistenceCoordinator, never()).persist(agentMessage);
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void internalSystemStateSyncIsPushedButNotPersisted() {
        SessionEventListener listener = new SessionEventListener(
                sessionManager, sseEmitter, messagePersistenceCoordinator);
        AgentMessage agentMessage = AgentMessage.builder()
                .messageId("sse-message-system")
                .sessionId("session-1")
                .agentId("agent-1")
                .type(MessageType.STATE_SYNC)
                .payload(Map.of("subtype", "system", "content", "worker started"))
                .build();

        listener.handleMessage(agentMessage);

        verify(messagePersistenceCoordinator, never()).persist(agentMessage);
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void durableHandlingPropagatesPersistenceFailureAndDoesNotEmitSse() {
        SessionEventListener listener = new SessionEventListener(
                sessionManager, sseEmitter, messagePersistenceCoordinator);
        AgentMessage agentMessage = AgentMessage.of(
                "session-1", "codex-worker", MessageType.TEXT_COMPLETE,
                Map.of("content", "assistant reply"));
        doThrow(new IllegalStateException("database unavailable"))
                .when(messagePersistenceCoordinator).persist(agentMessage);

        assertThrows(SessionEventListener.MessagePersistenceException.class,
                () -> listener.handleMessageDurably(agentMessage));

        verify(sseEmitter, never()).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void durableHandlingIsNotRepeatedByAsyncEventEntryPoint() {
        SessionEventListener listener = new SessionEventListener(
                sessionManager, sseEmitter, messagePersistenceCoordinator);
        AgentMessage agentMessage = AgentMessage.of(
                "session-1", "codex-worker", MessageType.TEXT_COMPLETE,
                Map.of("content", "assistant reply"));

        listener.handleMessageDurably(agentMessage);
        listener.onAgentMessage(agentMessage);

        verify(messagePersistenceCoordinator, times(1)).persist(agentMessage);
        verify(sseEmitter, times(1)).sendSessionEvent("session-1", agentMessage);
    }

    @Test
    void longStableMessageIdIsCompactedAndReplayUsesTheSameDatabaseSafeKey() {
        SessionEventListener listener = new SessionEventListener(
                sessionManager, sseEmitter, messagePersistenceCoordinator);
        String sourceId = "gemini-event:123e4567-e89b-12d3-a456-426614174000:987654321:assistant:"
                + "extra-provider-replay-suffix";
        AgentMessage first = AgentMessage.builder()
                .messageId(sourceId)
                .sessionId("session-1")
                .agentId("gemini-worker")
                .type(MessageType.TEXT_COMPLETE)
                .payload(Map.of("content", "assistant reply"))
                .build();
        AgentMessage replay = AgentMessage.builder()
                .messageId(sourceId)
                .sessionId("session-1")
                .agentId("gemini-worker")
                .type(MessageType.TEXT_COMPLETE)
                .payload(Map.of("content", "assistant reply"))
                .build();

        listener.handleMessageDurably(first);
        listener.onAgentMessage(replay);

        assertEquals(64, first.getMessageId().length());
        assertNotEquals(sourceId, first.getMessageId());
        assertEquals(first.getMessageId(), replay.getMessageId());
        verify(messagePersistenceCoordinator, times(1)).persist(first);
        verify(sseEmitter, times(1)).sendSessionEvent("session-1", first);
    }

    @Test
    void nativeSubtaskUpdateIsPushedButNotPersistedAsChatHistory() {
        SessionEventListener listener = new SessionEventListener(
                sessionManager, sseEmitter, messagePersistenceCoordinator);
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

        verify(messagePersistenceCoordinator, never()).persist(agentMessage);
        verify(sseEmitter).sendSessionEvent("session-1", agentMessage);
    }
}
