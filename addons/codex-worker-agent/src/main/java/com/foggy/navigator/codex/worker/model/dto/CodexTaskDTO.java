package com.foggy.navigator.codex.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Codex 任务信息 DTO
 *
 * @deprecated 使用 {@link com.foggy.navigator.common.dto.DispatchTaskDTO} 替代。
 *             DispatchTaskDTO 是 Agent 无关的统一任务视图，覆盖所有字段。
 *             本 DTO 仅保留用于 CodexTaskController 旧端点的向后兼容。
 */
@Deprecated(since = "unified-task-dispatch-refactor")
@Data
@Builder
public class CodexTaskDTO {
    private String taskId;
    private String workerTaskId;
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
    private Integer lastAckedSeq;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
