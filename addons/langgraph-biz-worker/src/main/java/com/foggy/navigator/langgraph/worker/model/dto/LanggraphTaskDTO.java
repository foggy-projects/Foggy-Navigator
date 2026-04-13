package com.foggy.navigator.langgraph.worker.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LanggraphTaskDTO {
    private String taskId;
    private String sessionId;
    private String workerId;
    private String userId;
    private String prompt;
    private String status;
    private String model;
    private String modelConfigId;
    private String directoryId;
    private String cwd;
    private String contextId;
    private String resultText;
    private String structuredOutput;
    private String errorMessage;
    private Long durationMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
