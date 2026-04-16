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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

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
}
