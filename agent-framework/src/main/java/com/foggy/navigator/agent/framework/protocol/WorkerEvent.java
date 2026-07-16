package com.foggy.navigator.agent.framework.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.foggy.navigator.agent.framework.diagnostic.ErrorCategory;
import com.foggy.navigator.agent.framework.diagnostic.ErrorRuntimePhase;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
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

    // ── Provider-neutral structured error summary (all optional) ──

    @JsonProperty("error_code")
    private String errorCode;
    @JsonProperty("error_message")
    private String errorMessage;
    @JsonProperty("error_category")
    private ErrorCategory errorCategory;
    @JsonProperty("runtime_phase")
    private ErrorRuntimePhase runtimePhase;
    private Boolean recoverable;
    @JsonProperty("diagnostic_ref")
    private String diagnosticRef;
    @JsonProperty("occurred_at")
    private Instant occurredAt;
    @JsonProperty("provider_type")
    private String providerType;
    @JsonProperty("runtime_type")
    private String runtimeType;
    @JsonProperty("exception_type")
    private String exceptionType;
    @JsonProperty("diagnostic_text")
    private String diagnosticText;
    @JsonProperty("provider_status")
    private String providerStatus;
    @JsonProperty("http_status")
    private Integer httpStatus;
    @JsonProperty("retry_count")
    private Integer retryCount;

    /**
     * Explicit provider/Worker confirmation that this event carries a durable
     * terminal observation.  A bare {@code error} remains diagnostic only:
     * transport loss, cancellation acknowledgement, and local SDK cleanup
     * can all emit an error before the remote task outcome is known.
     */
    @JsonProperty("terminal_observed")
    private Boolean terminalObserved;

    /**
     * Terminal status asserted only when {@link #terminalObserved} is true.
     * Error events currently accept FAILED or ABORTED; result events retain
     * their established COMPLETED semantics for backwards compatibility.
     */
    @JsonProperty("terminal_status")
    private String terminalStatus;

    /** Optional provenance for an observed terminal outcome, e.g. provider event. */
    @JsonProperty("terminal_source")
    private String terminalSource;

    /**
     * Codex App Server item identity. It associates text deltas with the
     * completed agent message so client renderers never merge separate items.
     */
    @JsonProperty("stream_id")
    private String streamId;

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
