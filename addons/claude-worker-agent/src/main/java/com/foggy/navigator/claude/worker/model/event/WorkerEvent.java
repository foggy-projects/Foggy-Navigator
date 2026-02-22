package com.foggy.navigator.claude.worker.model.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Worker SSE 事件 POJO
 * 对应 Agent Worker 返回的 SSE JSON 数据
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
    @JsonProperty("permission_id")
    private String permissionId;
    private java.util.List<Map<String, Object>> questions;
    @JsonProperty("checkpoint_id")
    private String checkpointId;
    @JsonProperty("tool_use_id")
    private String toolUseId;
    @JsonProperty("is_error")
    private Boolean isError;
    private String subtype;
    private Map<String, Object> data;
}
