package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 任务信息 DTO
 */
@Data
@Builder
public class TaskDTO {
    private String taskId;
    private String sessionId;
    private String workerId;
    private String prompt;
    private String cwd;
    private String status;
    private String claudeSessionId;
    private BigDecimal costUsd;
    private Long inputTokens;
    private Long outputTokens;
    private Long durationMs;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
