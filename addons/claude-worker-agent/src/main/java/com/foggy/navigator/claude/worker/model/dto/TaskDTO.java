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
    private String directoryId;
    private String status;
    private String claudeSessionId;
    private BigDecimal costUsd;
    private Long inputTokens;
    private Long outputTokens;
    private Long durationMs;
    private Integer numTurns;
    private String model;
    private String errorMessage;
    /** A2A 异步任务完成后保存的结果文本 */
    private String resultText;
    /** A2A 多轮会话标识 */
    private String contextId;
    /** JSON array of checkpoint objects */
    private String checkpoints;
    /** Whether file checkpointing was enabled for this task */
    private Boolean fileCheckpointingEnabled;
    /** Task source: "PLATFORM" or "SYNCED" */
    private String source;
    /** Agent Teams 配置 ID（任务创建时锁定） */
    private String agentTeamsConfigId;
    /** 仅 /active 端点填充：工作目录名称 */
    private String directoryName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
