package com.foggy.navigator.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.model.entity.EventEntity;
import com.foggy.navigator.api.repository.EventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EventService {

    private final Map<String, List<Event>> conversationEvents = new ConcurrentHashMap<>();
    private final Map<String, String> latestEventIds = new ConcurrentHashMap<>();

    @Autowired
    private EventRepository eventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public void saveEvent(Event event) {
        log.debug("保存事件: conversationId={}, kind={}", event.getConversationId(), event.getKind());

        if (event.getId() == null) {
            event.setId(UUID.randomUUID().toString());
        }

        if (event.getTimestamp() == null) {
            event.setTimestamp(LocalDateTime.now());
        }

        conversationEvents.computeIfAbsent(event.getConversationId(), k -> new ArrayList<>()).add(event);
        latestEventIds.put(event.getConversationId(), event.getId());

        saveEventToDatabase(event);
    }

    public List<Event> getEvents(String conversationId, Event.EventKind kind, LocalDateTime timestampGte, LocalDateTime timestampLt, String pageId, int limit) {
        List<Event> events = conversationEvents.get(conversationId);
        if (events == null) {
            if (timestampGte != null) {
                events = loadEventsFromDatabase(conversationId, kind, timestampGte, limit);
            } else {
                events = loadEventsFromDatabase(conversationId, kind, limit);
            }
            if (events != null) {
                conversationEvents.put(conversationId, events);
            }
        }

        return events.stream()
                .filter(event -> kind == null || event.getKind().equals(kind))
                .filter(event -> timestampGte == null || !event.getTimestamp().isBefore(timestampGte))
                .filter(event -> timestampLt == null || event.getTimestamp().isBefore(timestampLt))
                .sorted((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<Event> getEventsSince(String conversationId, String lastEventId) {
        List<Event> events = conversationEvents.get(conversationId);
        if (events == null) {
            events = loadEventsFromDatabase(conversationId, null, 100);
            if (events != null) {
                conversationEvents.put(conversationId, events);
            }
        }

        if (lastEventId == null || lastEventId.isEmpty()) {
            return events.stream()
                    .sorted((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()))
                    .collect(Collectors.toList());
        }

        boolean found = false;
        List<Event> result = new ArrayList<>();
        for (Event event : events) {
            if (found) {
                result.add(event);
            } else if (event.getId().equals(lastEventId)) {
                found = true;
            }
        }

        return result;
    }

    public String getLatestEventId(String conversationId) {
        String latestEventId = latestEventIds.get(conversationId);
        if (latestEventId == null) {
            latestEventId = eventRepository.findTopNByConversationIdOrderByTimestampAsc(conversationId)
                    .stream()
                    .limit(1)
                    .findFirst()
                    .map(EventEntity::getEventId)
                    .orElse(null);
            if (latestEventId != null) {
                latestEventIds.put(conversationId, latestEventId);
            }
        }
        return latestEventIds.get(conversationId);
    }

    @Transactional
    protected void saveEventToDatabase(Event event) {
        try {
            EventEntity entity = EventEntity.builder()
                    .eventId(event.getId())
                    .conversationId(event.getConversationId())
                    .kind(mapEventKind(event.getKind()))
                    .timestamp(event.getTimestamp())
                    .data(convertDataToJson(event.getData()))
                    .build();
            eventRepository.save(entity);
        } catch (Exception e) {
            log.error("保存事件到数据库失败: eventId={}", event.getId(), e);
        }
    }

    protected List<Event> loadEventsFromDatabase(String conversationId, Event.EventKind kind, int limit) {
        try {
            return eventRepository.findByConversationIdOrderByTimestampAsc(conversationId)
                    .stream()
                    .filter(entity -> kind == null || entity.getKind().equals(mapEventKind(kind)))
                    .limit(limit)
                    .map(entity -> {
                        Event event = Event.builder()
                                .id(entity.getEventId())
                                .conversationId(entity.getConversationId())
                                .kind(mapEventKind(entity.getKind()))
                                .timestamp(entity.getTimestamp())
                                .build();
                        event.setData(convertDataToMap(entity.getData()));
                        return event;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("从数据库加载事件失败: conversationId={}", conversationId, e);
            return null;
        }
    }

    protected List<Event> loadEventsFromDatabase(String conversationId, Event.EventKind kind, LocalDateTime timestampGte, int limit) {
        try {
            return eventRepository.findByConversationIdAndTimestampAfterOrderByTimestampAsc(conversationId, timestampGte)
                    .stream()
                    .filter(entity -> kind == null || entity.getKind().equals(mapEventKind(kind)))
                    .limit(limit)
                    .map(entity -> {
                        Event event = Event.builder()
                                .id(entity.getEventId())
                                .conversationId(entity.getConversationId())
                                .kind(mapEventKind(entity.getKind()))
                                .timestamp(entity.getTimestamp())
                                .build();
                        event.setData(convertDataToMap(entity.getData()));
                        return event;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("从数据库加载事件失败: conversationId={}", conversationId, e);
            return null;
        }
    }

    private EventEntity.EventKind mapEventKind(Event.EventKind kind) {
        return EventEntity.EventKind.valueOf(kind.name());
    }

    private Event.EventKind mapEventKind(EventEntity.EventKind kind) {
        return Event.EventKind.valueOf(kind.name());
    }

    private String convertDataToJson(Map<String, Object> data) {
        try {
            if (data == null || data.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.error("转换数据到 JSON 失败", e);
            return "{}";
        }
    }

    private Map<String, Object> convertDataToMap(String data) {
        try {
            if (data == null || data.isEmpty()) {
                return Map.of();
            }
            return objectMapper.readValue(data, Map.class);
        } catch (Exception e) {
            log.error("转换 JSON 到数据失败", e);
            return Map.of();
        }
    }
}
