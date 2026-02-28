package com.foggy.navigator.monitoring.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "monitoring_events", indexes = {
        @Index(name = "idx_service_type", columnList = "service, eventType"),
        @Index(name = "idx_event_time", columnList = "eventTime"),
        @Index(name = "idx_level", columnList = "level")
})
@Getter
@Setter
@NoArgsConstructor
public class MonitorEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String service;

    @Column(length = 128)
    private String instance;

    @Column(nullable = false, length = 32)
    private String eventType;

    @Column(length = 16)
    private String level;

    @Column(length = 256)
    private String loggerName;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(columnDefinition = "TEXT")
    private String stackTrace;

    @Column(columnDefinition = "JSON")
    private String extraData;

    @Column(nullable = false)
    private LocalDateTime eventTime;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
