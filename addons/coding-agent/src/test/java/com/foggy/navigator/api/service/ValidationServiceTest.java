package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.model.CreateConversationRequest;
import com.foggy.navigator.foundation.git.OpenHandsClient;
import com.foggy.navigator.foundation.git.model.OpenHandsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ValidationServiceTest {

    @Mock
    private OpenHandsClient openHandsClient;

    @Mock
    private EventService eventService;

    @InjectMocks
    private ValidationService validationService;

    @BeforeEach
    void setUp() {
    }

    @Test
    void testTriggerValidation_Success() {
        String conversationId = "conv-123";

        validationService.triggerValidation(conversationId);

        verify(openHandsClient).post(eq("/app-conversations/" + conversationId + "/validate"), anyMap(), eq(Void.class));

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventService).saveEvent(eventCaptor.capture());

        Event savedEvent = eventCaptor.getValue();
        assertEquals(conversationId, savedEvent.getConversationId());
        assertEquals(Event.EventKind.VALIDATION_TRIGGERED, savedEvent.getKind());
        assertNotNull(savedEvent.getData());
        assertTrue(savedEvent.getData().containsKey("timestamp"));
    }

    @Test
    void testTriggerValidation_Failure() {
        String conversationId = "conv-123";

        doThrow(new RuntimeException("API Error"))
                .when(openHandsClient).post(anyString(), any(), eq(Void.class));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            validationService.triggerValidation(conversationId);
        });

        assertTrue(exception.getMessage().contains("触发验证失败"));

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventService).saveEvent(eventCaptor.capture());

        Event savedEvent = eventCaptor.getValue();
        assertEquals(Event.EventKind.ERROR, savedEvent.getKind());
        assertTrue(savedEvent.getData().containsKey("error"));
    }

    @Test
    void testGetValidationResults_Success() {
        String conversationId = "conv-123";

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

    @Test
    void testTriggerValidation_MultipleCalls() {
        String conversationId = "conv-123";

        validationService.triggerValidation(conversationId);
        validationService.triggerValidation(conversationId);
        validationService.triggerValidation(conversationId);

        verify(openHandsClient, times(3)).post(eq("/app-conversations/" + conversationId + "/validate"), anyMap(), eq(Void.class));
        verify(eventService, times(3)).saveEvent(any(Event.class));
    }

    @Test
    void testGetValidationResults_WithParameters() {
        String conversationId = "conv-123";

        when(openHandsClient.searchEvents(
                eq(conversationId),
                eq("VALIDATION_RESULT"),
                isNull(),
                isNull(),
                isNull(),
                eq(100)
        )).thenReturn(List.of());

        validationService.getValidationResults(conversationId);

        verify(openHandsClient).searchEvents(
                eq(conversationId),
                eq("VALIDATION_RESULT"),
                isNull(),
                isNull(),
                isNull(),
                eq(100)
        );
    }
}
