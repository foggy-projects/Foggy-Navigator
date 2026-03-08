package com.foggy.navigator.codex.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Codex 任务信息 DTO
 */
@Data
@Builder
public class CodexTaskDTO {
    private String taskId;
    private String sessionId;
    private String directoryId;
    private String workerId;
    private String prompt;
    private String cwd;
    private String status;
    private String codexThreadId;
    private String model;
    private BigDecimal costUsd;
    private Long inputTokens;
    private Long outputTokens;
    private Long durationMs;
    private Integer numTurns;
    private String resultText;
    private String errorMessage;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
