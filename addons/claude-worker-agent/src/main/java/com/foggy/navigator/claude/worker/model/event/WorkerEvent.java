package com.foggy.navigator.claude.worker.model.event;

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
    private String taskId;
    private String sessionId;
    private String result;
    private BigDecimal costUsd;
    private Long durationMs;
    private Long inputTokens;
    private Long outputTokens;
    private String error;
}
