package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Open API 任务 DTO — 面向第三方的简化版任务状态
 */
@Data
@Builder
public class OpenApiTaskDTO {

    /** 任务 ID */
    private String taskId;

    /** Agent ID */
    private String agentId;

    /** 任务状态：SUBMITTED | WORKING | COMPLETED | FAILED | CANCELED */
    private String status;

    /** 多轮会话标识 */
    private String contextId;

    /** 执行结果（仅 COMPLETED 时返回） */
    private String result;

    /** 错误信息（仅 FAILED 时返回） */
    private String errorMessage;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    /** Token 费用（美元） */
    private BigDecimal costUsd;
}
