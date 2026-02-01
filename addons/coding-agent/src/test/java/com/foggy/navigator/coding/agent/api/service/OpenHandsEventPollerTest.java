package com.foggy.navigator.coding.agent.api.service;

import com.foggy.navigator.coding.agent.api.model.Event;
import com.foggy.navigator.coding.agent.api.model.OpenHandsPollingStartEvent;
import com.foggy.navigator.coding.agent.git.OpenHandsClient;
import com.foggy.navigator.coding.agent.git.OpenHandsClientFactory;
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

    // ===== convertOhEvent tests =====

    @Test
    void testConvertOhEvent_TerminalAction() {
        Map<String, Object> ohEvent = Map.of(
                "id", "uuid-1",
                "kind", "TerminalAction",
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
                "id", "uuid-2",
                "kind", "TerminalObservation",
                "content", "file1.txt file2.txt"
        );

        Event event = poller.convertOhEvent("conv-123", ohEvent);

        assertNotNull(event);
        assertEquals(Event.EventKind.AGENT_OBSERVATION, event.getKind());
    }

    @Test
    void testConvertOhEvent_ErrorEvent() {
        Map<String, Object> ohEvent = Map.of(
                "id", "uuid-3",
                "kind", "ConversationErrorEvent",
                "detail", "Something failed"
        );

        Event event = poller.convertOhEvent("conv-123", ohEvent);

        assertNotNull(event);
        assertEquals(Event.EventKind.ERROR, event.getKind());
    }

    @Test
    void testConvertOhEvent_StateUpdate() {
        Map<String, Object> ohEvent = Map.of(
                "id", "uuid-4",
                "kind", "ConversationStateUpdateEvent",
                "key", "execution_status",
                "value", "running"
        );

        Event event = poller.convertOhEvent("conv-123", ohEvent);

        assertNotNull(event);
        assertEquals(Event.EventKind.CONVERSATION_STATUS, event.getKind());
    }

    @Test
    void testConvertOhEvent_MessageEvent() {
        Map<String, Object> ohEvent = Map.of(
                "id", "uuid-5",
                "kind", "MessageEvent",
                "source", "user"
        );

        Event event = poller.convertOhEvent("conv-123", ohEvent);

        assertNotNull(event);
        assertEquals(Event.EventKind.MESSAGE_SENT, event.getKind());
    }

    @Test
    void testConvertOhEvent_SystemPrompt_SkippedAsNull() {
        Map<String, Object> ohEvent = Map.of(
                "id", "uuid-0",
                "kind", "SystemPromptEvent"
        );

        Event event = poller.convertOhEvent("conv-123", ohEvent);

        // SystemPromptEvent is too large, should be skipped
        assertNull(event);
    }

    @Test
    void testConvertOhEvent_MissingKind_ReturnsNull() {
        Map<String, Object> ohEvent = Map.of("id", "uuid-x", "data", "something");

        Event event = poller.convertOhEvent("conv-123", ohEvent);

        assertNull(event);
    }

    @Test
    void testConvertOhEvent_FallbackToType() {
        // If "kind" is missing, should fall back to "type"
        Map<String, Object> ohEvent = Map.of(
                "id", "uuid-6",
                "type", "CmdRunAction",
                "command", "echo hello"
        );

        Event event = poller.convertOhEvent("conv-123", ohEvent);

        assertNotNull(event);
        assertEquals(Event.EventKind.AGENT_ACTION, event.getKind());
    }

    // ===== isTerminalEvent tests =====

    @Test
    void testIsTerminalEvent_ConversationErrorEvent() {
        Map<String, Object> ohEvent = Map.of(
                "kind", "ConversationErrorEvent",
                "code", "LLMBadRequestError",
                "detail", "model not found"
        );

        assertTrue(poller.isTerminalEvent(ohEvent));
    }

    @Test
    void testIsTerminalEvent_ExecutionStatusError() {
        Map<String, Object> ohEvent = Map.of(
                "kind", "ConversationStateUpdateEvent",
                "key", "execution_status",
                "value", "error"
        );

        assertTrue(poller.isTerminalEvent(ohEvent));
    }

    @Test
    void testIsTerminalEvent_ExecutionStatusCompleted() {
        Map<String, Object> ohEvent = Map.of(
                "kind", "ConversationStateUpdateEvent",
                "key", "execution_status",
                "value", "completed"
        );

        assertTrue(poller.isTerminalEvent(ohEvent));
    }

    @Test
    void testIsTerminalEvent_ExecutionStatusRunning_NotTerminal() {
        Map<String, Object> ohEvent = Map.of(
                "kind", "ConversationStateUpdateEvent",
                "key", "execution_status",
                "value", "running"
        );

        assertFalse(poller.isTerminalEvent(ohEvent));
    }

    @Test
    void testIsTerminalEvent_NonStateEvent_NotTerminal() {
        Map<String, Object> ohEvent = Map.of(
                "kind", "TerminalAction",
                "command", "ls"
        );

        assertFalse(poller.isTerminalEvent(ohEvent));
    }

    @Test
    void testIsTerminalEvent_MessageEvent_NotTerminal() {
        Map<String, Object> ohEvent = Map.of(
                "kind", "MessageEvent",
                "source", "user"
        );

        assertFalse(poller.isTerminalEvent(ohEvent));
    }

    // ===== Polling lifecycle tests =====

    @Test
    void testStartPolling_DuplicateIgnored() {
        lenient().when(clientFactory.getClientForUser("user-1")).thenReturn(openHandsClient);
        lenient().when(openHandsClient.getNewEvents(anyString(), any()))
                .thenReturn(Map.of("items", List.of()));

        poller.startPolling("conv-123", "user-1", "oh-conv-456");
        assertTrue(poller.isPolling("conv-123"));

        // Start again - should be ignored (no error)
        poller.startPolling("conv-123", "user-1", "oh-conv-456");
        assertTrue(poller.isPolling("conv-123"));
    }

    @Test
    void testStopPolling() {
        lenient().when(clientFactory.getClientForUser("user-1")).thenReturn(openHandsClient);
        lenient().when(openHandsClient.getNewEvents(anyString(), any()))
                .thenReturn(Map.of("items", List.of()));

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
        lenient().when(openHandsClient.getNewEvents(anyString(), any()))
                .thenReturn(Map.of("items", List.of()));

        OpenHandsPollingStartEvent startEvent = new OpenHandsPollingStartEvent(
                "conv-123", "user-1", "oh-conv-456");

        poller.onPollingStart(startEvent);

        assertTrue(poller.isPolling("conv-123"));
    }
}
