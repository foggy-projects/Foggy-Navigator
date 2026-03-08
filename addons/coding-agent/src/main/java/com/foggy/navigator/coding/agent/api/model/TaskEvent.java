package com.foggy.navigator.coding.agent.api.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TaskEvent {
    private String id;
    private String taskId;
    private TaskEventType type;
    private String message;
    private Object data;
    private LocalDateTime timestamp;

    public enum TaskEventType {
        TASK_STARTED,
        AGENT_MESSAGE,
        TOOL_CALL_REQUEST,
        TOOL_CALL_APPROVED,
        TOOL_CALL_REJECTED,
        USER_INPUT_REQUEST,
        USER_INPUT_PROVIDED,
        VALIDATION_RESULT,
        TASK_COMPLETED,
        TASK_FAILED,
        TASK_ABORTED
    }
}
