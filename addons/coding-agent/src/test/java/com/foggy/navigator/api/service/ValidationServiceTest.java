package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.model.Message;
import com.foggy.navigator.foundation.git.OpenHandsClient;
import com.foggy.navigator.foundation.git.OpenHandsClientFactory;
import com.foggy.navigator.foundation.git.model.OpenHandsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ValidationServiceTest {

    @Mock
    private OpenHandsClientFactory openHandsClientFactory;

    @Mock
    private OpenHandsClient openHandsClient;

    @Mock
    private ConversationService conversationService;

    @Mock
    private MessageService messageService;

    @Mock
    private EventService eventService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ValidationService validationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(validationService, "validationEnabled", true);
        ReflectionTestUtils.setField(validationService, "validationTimeout", 30000L);
    }

    @Test
    void testTriggerValidation_Success() {
        String conversationId = "conv-123";
        String sandboxId = "sandbox-xyz";

        Conversation conversation = Conversation.builder()
                .conversationId(conversationId)
                .sandboxId(sandboxId)
                .status(Conversation.ConversationStatus.READY)
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conversation);
        when(openHandsClientFactory.getClient(sandboxId)).thenReturn(openHandsClient);
        when(messageService.getMessages(conversationId, 10)).thenReturn(List.of());

        validationService.triggerValidation(conversationId);

        verify(openHandsClient).post(eq("/app-conversations/" + conversationId + "/validate"), anyMap(), eq(Void.class));

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        Event publishedEvent = eventCaptor.getValue();
        assertEquals(conversationId, publishedEvent.getConversationId());
        assertEquals(Event.EventKind.VALIDATION_TRIGGERED, publishedEvent.getKind());
        assertNotNull(publishedEvent.getData());
    }

    @Test
    void testTriggerValidation_ConversationNotFound() {
        String conversationId = "conv-123";

        when(conversationService.getConversation(conversationId)).thenReturn(null);

        validationService.triggerValidation(conversationId);

        verify(openHandsClient, never()).post(anyString(), any(), any());

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        Event publishedEvent = eventCaptor.getValue();
        assertEquals(Event.EventKind.ERROR, publishedEvent.getKind());
        assertTrue(publishedEvent.getData().get("error").toString().contains("对话不存在"));
    }

    @Test
    void testTriggerValidation_ConversationNotReady() {
        String conversationId = "conv-123";

        Conversation conversation = Conversation.builder()
                .conversationId(conversationId)
                .status(Conversation.ConversationStatus.STARTING)
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conversation);

        validationService.triggerValidation(conversationId);

        verify(openHandsClient, never()).post(anyString(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void testTriggerValidation_Disabled() {
        ReflectionTestUtils.setField(validationService, "validationEnabled", false);

        String conversationId = "conv-123";
        validationService.triggerValidation(conversationId);

        verify(conversationService, never()).getConversation(anyString());
        verify(openHandsClient, never()).post(anyString(), any(), any());
    }

    @Test
    void testTriggerValidation_Failure() {
        String conversationId = "conv-123";
        String sandboxId = "sandbox-xyz";

        Conversation conversation = Conversation.builder()
                .conversationId(conversationId)
                .sandboxId(sandboxId)
                .status(Conversation.ConversationStatus.READY)
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conversation);
        when(openHandsClientFactory.getClient(sandboxId)).thenReturn(openHandsClient);
        when(messageService.getMessages(conversationId, 10)).thenReturn(List.of());
        doThrow(new RuntimeException("API Error"))
                .when(openHandsClient).post(anyString(), any(), eq(Void.class));

        validationService.triggerValidation(conversationId);

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        Event publishedEvent = eventCaptor.getValue();
        assertEquals(Event.EventKind.ERROR, publishedEvent.getKind());
        assertTrue(publishedEvent.getData().containsKey("error"));
    }

    @Test
    void testGetValidationStatus_WithResult() {
        String conversationId = "conv-123";

        Event resultEvent = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.VALIDATION_RESULT)
                .data(Map.of("status", "PASSED"))
                .build();

        when(eventService.getEventsByKind(conversationId, Event.EventKind.VALIDATION_RESULT))
                .thenReturn(List.of(resultEvent));

        Event status = validationService.getValidationStatus(conversationId);

        assertNotNull(status);
        assertEquals(Event.EventKind.VALIDATION_RESULT, status.getKind());
    }

    @Test
    void testGetValidationStatus_InProgress() {
        String conversationId = "conv-123";

        Event triggeredEvent = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.VALIDATION_TRIGGERED)
                .data(Map.of("timestamp", System.currentTimeMillis()))
                .build();

        when(eventService.getEventsByKind(conversationId, Event.EventKind.VALIDATION_RESULT))
                .thenReturn(List.of());
        when(eventService.getEventsByKind(conversationId, Event.EventKind.VALIDATION_TRIGGERED))
                .thenReturn(List.of(triggeredEvent));

        Event status = validationService.getValidationStatus(conversationId);

        assertNotNull(status);
        assertEquals(Event.EventKind.VALIDATION_TRIGGERED, status.getKind());
        assertEquals("in_progress", status.getData().get("status"));
    }

    @Test
    void testGetValidationStatus_NotStarted() {
        String conversationId = "conv-123";

        when(eventService.getEventsByKind(conversationId, Event.EventKind.VALIDATION_RESULT))
                .thenReturn(List.of());
        when(eventService.getEventsByKind(conversationId, Event.EventKind.VALIDATION_TRIGGERED))
                .thenReturn(List.of());

        Event status = validationService.getValidationStatus(conversationId);

        assertNotNull(status);
        assertEquals(Event.EventKind.VALIDATION_RESULT, status.getKind());
        assertEquals("not_started", status.getData().get("status"));
    }

    @Test
    void testGetValidationResults_Success() {
        String conversationId = "conv-123";
        String sandboxId = "sandbox-xyz";

        Conversation conversation = Conversation.builder()
                .conversationId(conversationId)
                .sandboxId(sandboxId)
                .build();

        OpenHandsEvent event1 = OpenHandsEvent.builder()
                .id("event-1")
                .kind("VALIDATION_RESULT")
                .data(Map.of("status", "PASSED"))
                .build();

        OpenHandsEvent event2 = OpenHandsEvent.builder()
                .id("event-2")
                .kind("VALIDATION_RESULT")
                .data(Map.of("status", "FAILED"))
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conversation);
        when(openHandsClientFactory.getClient(sandboxId)).thenReturn(openHandsClient);
        when(openHandsClient.searchEvents(
                eq(conversationId),
                eq("VALIDATION_RESULT"),
                isNull(),
                isNull(),
                isNull(),
                eq(100)
        )).thenReturn(List.of(event1, event2));

        List<OpenHandsEvent> results = validationService.getValidationResults(conversationId);

        assertNotNull(results);
        assertEquals(2, results.size());
        assertEquals("event-1", results.get(0).getId());
        assertEquals("event-2", results.get(1).getId());
    }

    @Test
    void testGetValidationResults_Empty() {
        String conversationId = "conv-123";
        String sandboxId = "sandbox-xyz";

        Conversation conversation = Conversation.builder()
                .conversationId(conversationId)
                .sandboxId(sandboxId)
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conversation);
        when(openHandsClientFactory.getClient(sandboxId)).thenReturn(openHandsClient);
        when(openHandsClient.searchEvents(
                eq(conversationId),
                eq("VALIDATION_RESULT"),
                isNull(),
                isNull(),
                isNull(),
                eq(100)
        )).thenReturn(List.of());

        List<OpenHandsEvent> results = validationService.getValidationResults(conversationId);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testGetValidationResults_Failure() {
        String conversationId = "conv-123";
        String sandboxId = "sandbox-xyz";

        Conversation conversation = Conversation.builder()
                .conversationId(conversationId)
                .sandboxId(sandboxId)
                .build();

        when(conversationService.getConversation(conversationId)).thenReturn(conversation);
        when(openHandsClientFactory.getClient(sandboxId)).thenReturn(openHandsClient);
        when(openHandsClient.searchEvents(
                anyString(),
                anyString(),
                isNull(),
                isNull(),
                isNull(),
                eq(100)
        )).thenThrow(new RuntimeException("API Error"));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            validationService.getValidationResults(conversationId);
        });

        assertTrue(exception.getMessage().contains("获取验证结果失败"));
    }
}
