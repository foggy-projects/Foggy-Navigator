package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 任务信息 DTO
 *
 * @deprecated 使用 {@link com.foggy.navigator.common.dto.DispatchTaskDTO} 替代。
 *             DispatchTaskDTO 是 Agent 无关的统一任务视图，覆盖所有字段。
 *             本 DTO 仅保留用于 ClaudeTaskController 旧端点的向后兼容。
 */
@Deprecated(since = "unified-task-dispatch-refactor")
@Data
@Builder
public class TaskDTO {
    private String taskId;
    private String workerTaskId;
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
    /** 创建任务时使用的平台 LLM 模型配置 ID */
    private String modelConfigId;
    private String errorMessage;
    /** A2A 异步任务完成后保存的结果文本 */
    private String resultText;
    /** A2A 多轮会话标识 */
    private String contextId;
    /** JSON array of checkpoint objects */
    private String checkpoints;
    private Integer lastAckedSeq;
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
