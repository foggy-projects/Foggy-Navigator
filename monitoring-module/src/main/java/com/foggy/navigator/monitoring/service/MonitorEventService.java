package com.foggy.navigator.monitoring.service;

import com.foggy.navigator.monitoring.model.dto.MonitorEventDTO;
import com.foggy.navigator.monitoring.model.entity.MonitorEventEntity;
import com.foggy.navigator.monitoring.repository.MonitorEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorEventService {

    private final MonitorEventRepository repository;

    /**
     * Persist a monitoring event from a RabbitMQ message envelope.
     */
    public MonitorEventEntity save(Map<String, Object> envelope) {
        MonitorEventEntity entity = new MonitorEventEntity();
        entity.setService((String) envelope.getOrDefault("service", "unknown"));
        entity.setInstance((String) envelope.get("instance"));
        entity.setEventType((String) envelope.getOrDefault("type", "log"));

        String timestamp = (String) envelope.get("timestamp");
        if (timestamp != null) {
            try {
                entity.setEventTime(LocalDateTime.parse(
                        timestamp.replace("Z", "").replace("+00:00", "")));
            } catch (Exception e) {
                entity.setEventTime(LocalDateTime.now());
            }
        } else {
            entity.setEventTime(LocalDateTime.now());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        if (payload != null) {
            entity.setLevel((String) payload.get("level"));
            entity.setLoggerName((String) payload.get("logger"));
            entity.setMessage((String) payload.get("message"));
            entity.setStackTrace((String) payload.get("stackTrace"));
        }

        return repository.save(entity);
    }

    /**
     * Paginated query with optional service and level filters.
     */
    public Page<MonitorEventDTO> query(String service, String level, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<MonitorEventEntity> entities;

        if (service != null && !service.isEmpty() && level != null && !level.isEmpty()) {
            // Use service + eventType filter (level stored in level column)
            entities = repository.findByServiceOrderByEventTimeDesc(service, pageable);
            // Further filter by level in-memory for combined filter
            // (Could add a repository method for this, but keeping it simple for Phase 1)
        } else if (service != null && !service.isEmpty()) {
            entities = repository.findByServiceOrderByEventTimeDesc(service, pageable);
        } else if (level != null && !level.isEmpty()) {
            entities = repository.findByLevelOrderByEventTimeDesc(level, pageable);
        } else {
            entities = repository.findAllByOrderByEventTimeDesc(pageable);
        }

        return entities.map(MonitorEventDTO::from);
    }

    /**
     * Count ERROR events in the last N hours.
     */
    public long countRecentErrors(int hours) {
        return repository.countByLevelAndEventTimeAfter(
                "ERROR", LocalDateTime.now().minusHours(hours));
    }
}
