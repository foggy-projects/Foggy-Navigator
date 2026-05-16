package com.foggy.navigator.common.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 统一任务 DTO —— 屏蔽 Claude / Codex 等后端差异，
 * 面向 Controller / 前端 / OpenAPI 的唯一任务视图。
 */
@Data
@Builder
public class DispatchTaskDTO {

    // ── 公共字段 ──
    private String taskId;
    private String workerTaskId;
    private String sessionId;
    private String parentSessionId;
    private String workerId;
    private String userId;
    private String agentId;
    private String providerType;
    private String prompt;
    private String cwd;
    private String directoryId;
    private String status;
    private String model;
    /** 创建任务时使用的平台 LLM 模型配置 ID */
    private String modelConfigId;
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

    // Session summary fields populated by paged history APIs.
    private Integer sessionTaskCount;
    private BigDecimal sessionTotalCostUsd;
    private Long sessionInputTokens;
    private Long sessionOutputTokens;
    private String sessionFirstPrompt;

    // ── 扩展字段（nullable，Provider 特有） ──
    /** Claude: 内部会话 ID */
    private String claudeSessionId;
    /** Codex: 线程 ID */
    private String codexThreadId;
    /** Gemini: 会话 ID */
    private String geminiSessionId;
    /** Claude: 检查点 JSON */
    private String checkpoints;
    /** Claude: 文件检查点开关 */
    private Boolean fileCheckpointingEnabled;
    /** A2A: 多轮上下文 ID */
    private String contextId;
    /** UI: 目录名称（冗余展示用） */
    private String directoryName;
}
