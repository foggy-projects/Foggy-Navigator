package com.foggy.navigator.coding.agent.api.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "events")
public class EventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String eventId;

    @Column(nullable = false, length = 64)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EventKind kind;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(columnDefinition = "JSON")
    private String data;

    public enum EventKind {
        CONVERSATION_STATUS,
        MESSAGE_SENT,
        AGENT_ACTION,
        AGENT_OBSERVATION,
        VALIDATION_TRIGGERED,
        VALIDATION_RESULT,
        ERROR
    }
}
