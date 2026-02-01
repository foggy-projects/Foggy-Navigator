package com.foggy.navigator.coding.agent.api.service;

import com.foggy.navigator.coding.agent.api.model.Event;
import com.foggy.navigator.coding.agent.api.model.entity.EventEntity;
import com.foggy.navigator.coding.agent.api.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private EventService eventService;

    @Test
    void testSaveEvent_Success() {
        String conversationId = "conv-123";
        Event event = Event.builder()
                .id("event-1")
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .data(Map.of("messageId", "msg-1", "content", "Test"))
                .timestamp(LocalDateTime.now())
                .build();

        eventService.saveEvent(event);

        ArgumentCaptor<EventEntity> entityCaptor = ArgumentCaptor.forClass(EventEntity.class);
        verify(eventRepository).save(entityCaptor.capture());

        EventEntity savedEntity = entityCaptor.getValue();
        assertEquals(event.getId(), savedEntity.getEventId());
        assertEquals(conversationId, savedEntity.getConversationId());
        assertEquals(EventEntity.EventKind.MESSAGE_SENT, savedEntity.getKind());
        assertNotNull(savedEntity.getData());
    }

    @Test
    void testGetEvents_Empty() {
        String conversationId = "conv-123";

        List<Event> events = eventService.getEvents(conversationId, null, null, null, null, 10);

        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void testGetEvents_WithEvents() {
        String conversationId = "conv-123";

        Event event1 = Event.builder()
                .id("event-1")
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .timestamp(LocalDateTime.now().minusMinutes(2))
                .build();

        Event event2 = Event.builder()
                .id("event-2")
                .conversationId(conversationId)
                .kind(Event.EventKind.CONVERSATION_STATUS)
                .timestamp(LocalDateTime.now().minusMinutes(1))
                .build();

        eventService.saveEvent(event1);
        eventService.saveEvent(event2);

        List<Event> events = eventService.getEvents(conversationId, null, null, null, null, 10);

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("event-1", events.get(0).getId());
        assertEquals("event-2", events.get(1).getId());
    }

    @Test
    void testGetEvents_WithKindFilter() {
        String conversationId = "conv-123";

        Event event1 = Event.builder()
                .id("event-1")
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .timestamp(LocalDateTime.now().minusMinutes(2))
                .build();

        Event event2 = Event.builder()
                .id("event-2")
                .conversationId(conversationId)
                .kind(Event.EventKind.CONVERSATION_STATUS)
                .timestamp(LocalDateTime.now().minusMinutes(1))
                .build();

        eventService.saveEvent(event1);
        eventService.saveEvent(event2);

        List<Event> events = eventService.getEvents(conversationId, Event.EventKind.MESSAGE_SENT, null, null, null, 10);

        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals("event-1", events.get(0).getId());
        assertEquals(Event.EventKind.MESSAGE_SENT, events.get(0).getKind());
    }

    @Test
    void testGetEvents_WithTimestampFilter() {
        String conversationId = "conv-123";
        LocalDateTime now = LocalDateTime.now();

        Event event1 = Event.builder()
                .id("event-1")
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .timestamp(now.minusMinutes(2))
                .build();

        Event event2 = Event.builder()
                .id("event-2")
                .conversationId(conversationId)
                .kind(Event.EventKind.CONVERSATION_STATUS)
                .timestamp(now.minusMinutes(1))
                .build();

        eventService.saveEvent(event1);
        eventService.saveEvent(event2);

        List<Event> events = eventService.getEvents(conversationId, null, now.minusMinutes(2), null, null, 10);

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("event-1", events.get(0).getId());
        assertEquals("event-2", events.get(1).getId());
    }

    @Test
    void testGetEvents_WithLimit() {
        String conversationId = "conv-123";

        for (int i = 1; i <= 5; i++) {
            Event event = Event.builder()
                    .id("event-" + i)
                    .conversationId(conversationId)
                    .kind(Event.EventKind.MESSAGE_SENT)
                    .timestamp(LocalDateTime.now().minusMinutes(i))
                    .build();
            eventService.saveEvent(event);
        }

        List<Event> events = eventService.getEvents(conversationId, null, null, null, null, 3);

        assertNotNull(events);
        assertEquals(3, events.size());
    }

    @Test
    void testGetEvents_FromDatabase() {
        String conversationId = "conv-123";

        EventEntity entity1 = EventEntity.builder()
                .id(1L)
                .eventId("event-1")
                .conversationId(conversationId)
                .kind(EventEntity.EventKind.MESSAGE_SENT)
                .timestamp(LocalDateTime.now().minusMinutes(2))
                .data("{\"messageId\":\"msg-1\"}")
                .build();

        EventEntity entity2 = EventEntity.builder()
                .id(2L)
                .eventId("event-2")
                .conversationId(conversationId)
                .kind(EventEntity.EventKind.CONVERSATION_STATUS)
                .timestamp(LocalDateTime.now().minusMinutes(1))
                .data("{\"status\":\"READY\"}")
                .build();

        when(eventRepository.findByConversationIdOrderByTimestampAsc(conversationId))
                .thenReturn(List.of(entity1, entity2));

        List<Event> events = eventService.getEvents(conversationId, null, null, null, null, 10);

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("event-1", events.get(0).getId());
        assertEquals("event-2", events.get(1).getId());
        assertEquals(Event.EventKind.MESSAGE_SENT, events.get(0).getKind());
        assertEquals(Event.EventKind.CONVERSATION_STATUS, events.get(1).getKind());
    }

    @Test
    void testGetLatestEventId_NoEvents() {
        String conversationId = "conv-123";

        String latestEventId = eventService.getLatestEventId(conversationId);

        assertNull(latestEventId);
    }

    @Test
    void testGetLatestEventId_WithEvents() {
        String conversationId = "conv-123";

        Event event1 = Event.builder()
                .id("event-1")
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .timestamp(LocalDateTime.now().minusMinutes(2))
                .build();

        Event event2 = Event.builder()
                .id("event-2")
                .conversationId(conversationId)
                .kind(Event.EventKind.CONVERSATION_STATUS)
                .timestamp(LocalDateTime.now().minusMinutes(1))
                .build();

        eventService.saveEvent(event1);
        eventService.saveEvent(event2);

        String latestEventId = eventService.getLatestEventId(conversationId);

        assertNotNull(latestEventId);
        assertEquals("event-2", latestEventId);
    }

    @Test
    void testGetLatestEventId_FromDatabase() {
        String conversationId = "conv-123";

        EventEntity entity = EventEntity.builder()
                .id(1L)
                .eventId("event-db-1")
                .conversationId(conversationId)
                .kind(EventEntity.EventKind.MESSAGE_SENT)
                .timestamp(LocalDateTime.now())
                .data("{}")
                .build();

        when(eventRepository.findTopNByConversationIdOrderByTimestampAsc(conversationId))
                .thenReturn(List.of(entity));

        String latestEventId = eventService.getLatestEventId(conversationId);

        assertNotNull(latestEventId);
        assertEquals("event-db-1", latestEventId);
    }

    @Test
    void testSaveEvent_WithData() {
        String conversationId = "conv-123";
        Map<String, Object> data = Map.of(
                "messageId", "msg-1",
                "content", "Test message",
                "userId", "user-123"
        );

        Event event = Event.builder()
                .id("event-1")
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();

        eventService.saveEvent(event);

        ArgumentCaptor<EventEntity> entityCaptor = ArgumentCaptor.forClass(EventEntity.class);
        verify(eventRepository).save(entityCaptor.capture());

        EventEntity savedEntity = entityCaptor.getValue();
        assertNotNull(savedEntity.getData());
        assertTrue(savedEntity.getData().contains("messageId"));
        assertTrue(savedEntity.getData().contains("content"));
        assertTrue(savedEntity.getData().contains("userId"));
    }

    @Test
    void testGetEventsByKind_Success() {
        String conversationId = "conv-123";

        Event event1 = Event.builder()
                .id("event-1")
                .conversationId(conversationId)
                .kind(Event.EventKind.VALIDATION_TRIGGERED)
                .timestamp(LocalDateTime.now().minusMinutes(2))
                .build();

        Event event2 = Event.builder()
                .id("event-2")
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .timestamp(LocalDateTime.now().minusMinutes(1))
                .build();

        Event event3 = Event.builder()
                .id("event-3")
                .conversationId(conversationId)
                .kind(Event.EventKind.VALIDATION_TRIGGERED)
                .timestamp(LocalDateTime.now())
                .build();

        eventService.saveEvent(event1);
        eventService.saveEvent(event2);
        eventService.saveEvent(event3);

        List<Event> validationEvents = eventService.getEventsByKind(conversationId, Event.EventKind.VALIDATION_TRIGGERED);

        assertNotNull(validationEvents);
        assertEquals(2, validationEvents.size());
        assertTrue(validationEvents.stream().allMatch(e -> e.getKind() == Event.EventKind.VALIDATION_TRIGGERED));
    }

    @Test
    void testGetEventsByKind_Empty() {
        String conversationId = "conv-123";

        Event event1 = Event.builder()
                .id("event-1")
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .timestamp(LocalDateTime.now())
                .build();

        eventService.saveEvent(event1);

        List<Event> validationEvents = eventService.getEventsByKind(conversationId, Event.EventKind.VALIDATION_RESULT);

        assertNotNull(validationEvents);
        assertTrue(validationEvents.isEmpty());
    }

    @Test
    void testGetEventsSince_WithEvents() {
        String conversationId = "conv-123";

        Event event1 = Event.builder()
                .id("event-1")
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .timestamp(LocalDateTime.now().minusMinutes(3))
                .build();

        Event event2 = Event.builder()
                .id("event-2")
                .conversationId(conversationId)
                .kind(Event.EventKind.CONVERSATION_STATUS)
                .timestamp(LocalDateTime.now().minusMinutes(2))
                .build();

        Event event3 = Event.builder()
                .id("event-3")
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .timestamp(LocalDateTime.now().minusMinutes(1))
                .build();

        eventService.saveEvent(event1);
        eventService.saveEvent(event2);
        eventService.saveEvent(event3);

        List<Event> eventsSince = eventService.getEventsSince(conversationId, "event-1");

        assertNotNull(eventsSince);
        assertEquals(2, eventsSince.size());
        assertEquals("event-2", eventsSince.get(0).getId());
        assertEquals("event-3", eventsSince.get(1).getId());
    }
}
