package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.model.OpenHandsPollingStartEvent;
import com.foggy.navigator.foundation.git.OpenHandsClient;
import com.foggy.navigator.foundation.git.OpenHandsClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class OpenHandsEventPollerTest {

    @Mock
    private OpenHandsClientFactory clientFactory;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ConversationService conversationService;

    @Mock
    private OpenHandsClient openHandsClient;

    @InjectMocks
    private OpenHandsEventPoller poller;

    @AfterEach
    void cleanup() {
        poller.shutdown();
    }

    @Test
    void testConvertOhEvent_Action() {
        Map<String, Object> ohEvent = Map.of(
                "id", 1,
                "type", "CmdRunAction",
                "command", "ls -la"
        );

        Event event = poller.convertOhEvent("conv-123", ohEvent);

        assertNotNull(event);
        assertEquals("conv-123", event.getConversationId());
        assertEquals(Event.EventKind.AGENT_ACTION, event.getKind());
        assertNotNull(event.getData());
    }

    @Test
    void testConvertOhEvent_Observation() {
        Map<String, Object> ohEvent = Map.of(
                "id", 2,
                "type", "CmdOutputObservation",
                "content", "file1.txt file2.txt"
        );

        Event event = poller.convertOhEvent("conv-123", ohEvent);

        assertNotNull(event);
        assertEquals(Event.EventKind.AGENT_OBSERVATION, event.getKind());
    }

    @Test
    void testConvertOhEvent_Error() {
        Map<String, Object> ohEvent = Map.of(
                "id", 3,
                "type", "ErrorObservation",
                "message", "Something failed"
        );

        Event event = poller.convertOhEvent("conv-123", ohEvent);

        assertNotNull(event);
        assertEquals(Event.EventKind.ERROR, event.getKind());
    }

    @Test
    void testConvertOhEvent_MissingType_ReturnsNull() {
        Map<String, Object> ohEvent = Map.of("id", 4, "data", "something");

        Event event = poller.convertOhEvent("conv-123", ohEvent);

        assertNull(event);
    }

    @Test
    void testExtractEventId_IntValue() {
        Map<String, Object> ohEvent = Map.of("id", 42, "type", "test");
        assertEquals(42, poller.extractEventId(ohEvent));
    }

    @Test
    void testExtractEventId_StringValue() {
        Map<String, Object> ohEvent = Map.of("id", "99", "type", "test");
        assertEquals(99, poller.extractEventId(ohEvent));
    }

    @Test
    void testExtractEventId_Missing_ReturnsZero() {
        Map<String, Object> ohEvent = Map.of("type", "test");
        assertEquals(0, poller.extractEventId(ohEvent));
    }

    @Test
    void testIsTerminalEvent_AgentFinished() {
        Map<String, Object> ohEvent = Map.of(
                "type", "agent_state_changed",
                "state", "finished"
        );

        assertTrue(poller.isTerminalEvent(ohEvent));
    }

    @Test
    void testIsTerminalEvent_AgentStopped() {
        Map<String, Object> ohEvent = Map.of(
                "type", "agent_state_changed",
                "state", "stopped"
        );

        assertTrue(poller.isTerminalEvent(ohEvent));
    }

    @Test
    void testIsTerminalEvent_AgentError() {
        Map<String, Object> ohEvent = Map.of(
                "type", "agent_state_changed",
                "state", "error"
        );

        assertTrue(poller.isTerminalEvent(ohEvent));
    }

    @Test
    void testIsTerminalEvent_AgentRunning_NotTerminal() {
        Map<String, Object> ohEvent = Map.of(
                "type", "agent_state_changed",
                "state", "running"
        );

        assertFalse(poller.isTerminalEvent(ohEvent));
    }

    @Test
    void testIsTerminalEvent_NonStateEvent_NotTerminal() {
        Map<String, Object> ohEvent = Map.of(
                "type", "CmdRunAction",
                "command", "ls"
        );

        assertFalse(poller.isTerminalEvent(ohEvent));
    }

    @Test
    void testIsTerminalEvent_FinishAction() {
        Map<String, Object> ohEvent = Map.of(
                "type", "AgentFinishAction",
                "message", "Done"
        );

        assertTrue(poller.isTerminalEvent(ohEvent));
    }

    @Test
    void testStartPolling_DuplicateIgnored() {
        // Lenient: scheduler thread may or may not call these before test ends
        lenient().when(clientFactory.getClientForUser("user-1")).thenReturn(openHandsClient);
        lenient().when(openHandsClient.getNewEvents(anyString(), anyString())).thenReturn(List.of());

        poller.startPolling("conv-123", "user-1", "oh-conv-456");
        assertTrue(poller.isPolling("conv-123"));

        // Start again - should be ignored (no error)
        poller.startPolling("conv-123", "user-1", "oh-conv-456");
        assertTrue(poller.isPolling("conv-123"));
    }

    @Test
    void testStopPolling() {
        // Lenient: scheduler thread may or may not call these before test ends
        lenient().when(clientFactory.getClientForUser("user-1")).thenReturn(openHandsClient);
        lenient().when(openHandsClient.getNewEvents(anyString(), anyString())).thenReturn(List.of());

        poller.startPolling("conv-123", "user-1", "oh-conv-456");
        assertTrue(poller.isPolling("conv-123"));

        poller.stopPolling("conv-123");
        assertFalse(poller.isPolling("conv-123"));
    }

    @Test
    void testStopPolling_NonExistent_NoError() {
        poller.stopPolling("non-existent");
        assertFalse(poller.isPolling("non-existent"));
    }

    @Test
    void testOnPollingStart_EventListener() {
        lenient().when(clientFactory.getClientForUser("user-1")).thenReturn(openHandsClient);
        lenient().when(openHandsClient.getNewEvents(anyString(), anyString())).thenReturn(List.of());

        OpenHandsPollingStartEvent startEvent = new OpenHandsPollingStartEvent(
                "conv-123", "user-1", "oh-conv-456");

        poller.onPollingStart(startEvent);

        assertTrue(poller.isPolling("conv-123"));
    }

    @Test
    void testIsTerminalEvent_AwaitingUserInput() {
        Map<String, Object> ohEvent = Map.of(
                "type", "agent_state_changed",
                "state", "awaiting_user_input"
        );

        assertTrue(poller.isTerminalEvent(ohEvent));
    }
}
