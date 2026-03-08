package com.foggy.navigator.coding.agent.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    private String id;

    private String conversationId;

    private EventKind kind;

    private LocalDateTime timestamp;

    private Map<String, Object> data;

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
