package com.foggy.navigator.codex.worker.model.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Worker SSE 事件 POJO
 * 与 Claude Worker 的 WorkerEvent 格式完全一致，
 * Codex Worker 输出兼容格式的 JSON。
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
    @JsonProperty("tool_use_id")
    private String toolUseId;
    @JsonProperty("is_error")
    private Boolean isError;
    private String subtype;
    private Map<String, Object> data;

    /** Monotonically increasing sequence number (ESN) */
    private Integer seq;

    @JsonProperty("latest_seq")
    private Integer latestSeq;

    @JsonProperty("event_count")
    private Integer eventCount;
}
