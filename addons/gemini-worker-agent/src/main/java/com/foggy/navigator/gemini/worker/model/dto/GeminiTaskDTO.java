package com.foggy.navigator.gemini.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Gemini 任务信息 DTO
 */
@Data
@Builder
public class GeminiTaskDTO {
    private String taskId;
    private String workerTaskId;
    private String sessionId;
    private String directoryId;
    private String workerId;
    private String prompt;
    private String cwd;
    private String status;
    private String geminiSessionId;
    private String model;
    private BigDecimal costUsd;
    private Long inputTokens;
    private Long outputTokens;
    private Long durationMs;
    private Integer numTurns;
    private String resultText;
    private String errorMessage;
    private Integer lastAckedSeq;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
