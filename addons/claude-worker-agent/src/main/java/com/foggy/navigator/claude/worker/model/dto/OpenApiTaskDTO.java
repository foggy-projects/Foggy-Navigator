package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Open API 任务 DTO — 面向第三方的简化版任务状态
 * <p>
 * 状态枚举：SUBMITTED | RUNNING | AWAITING_INPUT | COMPLETED | FAILED | CANCELLED
 */
@Data
@Builder
public class OpenApiTaskDTO {

    /** 任务 ID */
    private String taskId;

    /** Agent ID */
    private String agentId;

    /** 任务状态 */
    private String status;

    /** 多轮会话标识 */
    private String contextId;

    /** Worker 侧任务 ID */
    private String workerTaskId;

    /** Provider 侧任务 ID（当前等同 workerTaskId，便于上游统一诊断） */
    private String providerTaskId;

    /** 已同步的 Worker 事件序号 */
    private Integer lastAckedSeq;

    /** 执行结果（仅 COMPLETED 时返回） */
    private String result;

    /** 错误信息（仅 FAILED 时返回） */
    private String errorMessage;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    /** Token 费用（美元） */
    private BigDecimal costUsd;

    /** 任务创建时间 */
    private LocalDateTime createdAt;

    /** 任务最后更新时间 */
    private LocalDateTime updatedAt;
}
