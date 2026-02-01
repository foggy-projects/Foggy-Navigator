package com.foggy.navigator.coding.agent.api.listener;

import com.foggy.navigator.coding.agent.api.model.Conversation;
import com.foggy.navigator.coding.agent.api.model.Conversation.ConversationStatus;
import com.foggy.navigator.coding.agent.api.model.Event;
import com.foggy.navigator.coding.agent.api.model.OpenHandsPollingStartEvent;
import com.foggy.navigator.coding.agent.api.service.ConversationService;
import com.foggy.navigator.coding.agent.git.OpenHandsClient;
import com.foggy.navigator.coding.agent.git.OpenHandsClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenHandsMessageForwarderTest {

    @Mock
    private OpenHandsClientFactory clientFactory;

    @Mock
    private ConversationService conversationService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private OpenHandsClient openHandsClient;

    @InjectMocks
    private OpenHandsMessageForwarder forwarder;

    @Test
    void testMessageSent_ForwardsToOpenHands() {
        String conversationId = "conv-123";
        String ohConversationId = "oh-conv-456";
        String userId = "user-1";
        String content = "Create a file called hello.md";

        Conversation conv = Conversation.builder()
                .conversationId(conversationId)
                .ohConversationId(ohConversationId)
                .userId(userId)
                .status(ConversationStatus.READY)
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conv);
        when(clientFactory.getClientForUser(userId)).thenReturn(openHandsClient);

        Event event = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .data(Map.of("content", content, "messageId", "msg-1"))
                .build();

        forwarder.onMessageSent(event);

        verify(openHandsClient).sendMessage(ohConversationId, content);
        assertEquals(ConversationStatus.RUNNING, conv.getStatus());

        // Verify polling start event was published
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(1)).publishEvent(eventCaptor.capture());

        boolean pollingStartPublished = eventCaptor.getAllValues().stream()
                .anyMatch(e -> e instanceof OpenHandsPollingStartEvent);
        assertTrue(pollingStartPublished);
    }

    @Test
    void testMessageSent_ConversationNotFound_Skips() {
        String conversationId = "conv-not-found";

        when(conversationService.getConversation(conversationId)).thenReturn(null);

        Event event = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .data(Map.of("content", "test", "messageId", "msg-1"))
                .build();

        forwarder.onMessageSent(event);

        verifyNoInteractions(clientFactory);
    }

    @Test
    void testMessageSent_MissingOhConversationId_Skips() {
        String conversationId = "conv-123";

        Conversation conv = Conversation.builder()
                .conversationId(conversationId)
                .ohConversationId(null) // no OH conversation
                .userId("user-1")
                .status(ConversationStatus.READY)
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conv);

        Event event = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .data(Map.of("content", "test", "messageId", "msg-1"))
                .build();

        forwarder.onMessageSent(event);

        verifyNoInteractions(clientFactory);
    }

    @Test
    void testMessageSent_ConversationNotReady_Skips() {
        String conversationId = "conv-123";

        Conversation conv = Conversation.builder()
                .conversationId(conversationId)
                .ohConversationId("oh-conv-456")
                .userId("user-1")
                .status(ConversationStatus.ERROR) // not READY/RUNNING/IDLE
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conv);

        Event event = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .data(Map.of("content", "test", "messageId", "msg-1"))
                .build();

        forwarder.onMessageSent(event);

        verifyNoInteractions(clientFactory);
    }

    @Test
    void testMessageSent_RunningStatus_ForwardsSuccessfully() {
        String conversationId = "conv-123";
        String ohConversationId = "oh-conv-456";
        String userId = "user-1";

        Conversation conv = Conversation.builder()
                .conversationId(conversationId)
                .ohConversationId(ohConversationId)
                .userId(userId)
                .status(ConversationStatus.RUNNING) // RUNNING is also acceptable
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conv);
        when(clientFactory.getClientForUser(userId)).thenReturn(openHandsClient);

        Event event = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .data(Map.of("content", "do something", "messageId", "msg-1"))
                .build();

        forwarder.onMessageSent(event);

        verify(openHandsClient).sendMessage(ohConversationId, "do something");
    }

    @Test
    void testMessageSent_EmptyContent_Skips() {
        String conversationId = "conv-123";

        Conversation conv = Conversation.builder()
                .conversationId(conversationId)
                .ohConversationId("oh-conv-456")
                .userId("user-1")
                .status(ConversationStatus.READY)
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conv);

        Event event = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .data(Map.of("content", "", "messageId", "msg-1"))
                .build();

        forwarder.onMessageSent(event);

        verifyNoInteractions(clientFactory);
    }

    @Test
    void testMessageSent_ClientThrowsException_PublishesError() {
        String conversationId = "conv-123";
        String ohConversationId = "oh-conv-456";
        String userId = "user-1";

        Conversation conv = Conversation.builder()
                .conversationId(conversationId)
                .ohConversationId(ohConversationId)
                .userId(userId)
                .status(ConversationStatus.READY)
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conv);
        when(clientFactory.getClientForUser(userId)).thenReturn(openHandsClient);
        doThrow(new RuntimeException("Connection refused")).when(openHandsClient)
                .sendMessage(eq(ohConversationId), anyString());

        Event event = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .data(Map.of("content", "test", "messageId", "msg-1"))
                .build();

        forwarder.onMessageSent(event);

        // Should publish an ERROR event
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(1)).publishEvent(eventCaptor.capture());

        boolean errorPublished = eventCaptor.getAllValues().stream()
                .filter(e -> e instanceof Event)
                .map(e -> (Event) e)
                .anyMatch(e -> e.getKind() == Event.EventKind.ERROR);
        assertTrue(errorPublished);
    }
}
