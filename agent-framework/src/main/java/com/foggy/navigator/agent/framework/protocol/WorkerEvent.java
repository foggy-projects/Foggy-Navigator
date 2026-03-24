package com.foggy.navigator.agent.framework.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Worker SSE 事件 POJO —— 所有 Agent Worker（Claude / Codex / 未来）共用。
 * <p>
 * 对应 Agent Worker 返回的 SSE JSON 数据。
 * Codex Worker 输出与 Claude Worker 兼容格式。
 * Claude 特有字段（permissionId、checkpointId 等）在 Codex 中始终为 null。
 */
@Data
public class WorkerEvent {
    private String type;
    private String content;
    private String tool;
    private Map<String, Object> input;
    private String output;

    @JsonProperty("task_id")
    private String taskId;
    @JsonProperty("session_id")
    private String sessionId;

    private String result;

    @JsonProperty("cost_usd")
    private BigDecimal costUsd;
    @JsonProperty("duration_ms")
    private Long durationMs;
    @JsonProperty("input_tokens")
    private Long inputTokens;
    @JsonProperty("output_tokens")
    private Long outputTokens;
    @JsonProperty("num_turns")
    private Integer numTurns;

    private String model;
    private String error;

    // ── Claude 特有字段（Codex 中为 null） ──

    @JsonProperty("permission_id")
    private String permissionId;
    @JsonProperty("allowed_prompts")
    private List<Map<String, Object>> allowedPrompts;
    /** ExitPlanMode 规划内容（Markdown） */
    private String plan;
    private List<Map<String, Object>> questions;
    @JsonProperty("checkpoint_id")
    private String checkpointId;

    // ── 共用字段 ──

    @JsonProperty("tool_use_id")
    private String toolUseId;
    @JsonProperty("is_error")
    private Boolean isError;
    private String subtype;
    private Map<String, Object> data;

    // ── ESN (Event Sequence Number) ──

    /** Monotonically increasing sequence number injected by Worker EventBroadcast.
     *  null for events from old Workers without ESN support (backward compat). */
    private Integer seq;

    /** sync_checkpoint 事件专用：Worker 上该任务的最新 seq */
    @JsonProperty("latest_seq")
    private Integer latestSeq;

    /** sync_checkpoint 事件专用：Worker 上该任务的事件总数 */
    @JsonProperty("event_count")
    private Integer eventCount;
}
