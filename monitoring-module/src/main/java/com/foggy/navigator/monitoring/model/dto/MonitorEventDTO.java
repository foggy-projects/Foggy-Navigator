package com.foggy.navigator.monitoring.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MonitorEventDTO {
    private Long id;
    private String service;
    private String instance;
    private String eventType;
    private String level;
    private String loggerName;
    private String message;
    private String stackTrace;
    private LocalDateTime eventTime;
    private LocalDateTime createdAt;

    public static MonitorEventDTO from(
            com.foggy.navigator.monitoring.model.entity.MonitorEventEntity entity) {
        MonitorEventDTO dto = new MonitorEventDTO();
        dto.setId(entity.getId());
        dto.setService(entity.getService());
        dto.setInstance(entity.getInstance());
        dto.setEventType(entity.getEventType());
        dto.setLevel(entity.getLevel());
        dto.setLoggerName(entity.getLoggerName());
        dto.setMessage(entity.getMessage());
        dto.setStackTrace(entity.getStackTrace());
        dto.setEventTime(entity.getEventTime());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
