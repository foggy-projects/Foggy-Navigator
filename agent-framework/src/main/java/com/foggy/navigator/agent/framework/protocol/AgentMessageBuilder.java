package com.foggy.navigator.agent.framework.protocol;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentMessage 标准化构建器 —— 统一各 Agent 后端的消息 payload 字段命名。
 * <p>
 * 所有 Relay / Service 应通过此 Builder 构建 AgentMessage，
 * 而非直接手写 Map payload。这确保前端收到的事件结构一致，
 * 不随后端 Agent 类型变化。
 * <p>
 * 使用示例：
 * <pre>
 * AgentMessage msg = AgentMessageBuilder.create(sessionId, agentId)
 *     .taskId(taskId)
 *     .textComplete("Hello, world!")
 *     .build();
 * </pre>
 */
public class AgentMessageBuilder {

    private final String sessionId;
    private final String agentId;
    private String taskIdField;
    private MessageType type;
    private final Map<String, Object> payload = new LinkedHashMap<>();

    private AgentMessageBuilder(String sessionId, String agentId) {
        this.sessionId = sessionId;
        this.agentId = agentId;
    }

    public static AgentMessageBuilder create(String sessionId, String agentId) {
        return new AgentMessageBuilder(sessionId, agentId);
    }

    // ── 通用字段 ──

    public AgentMessageBuilder taskId(String taskId) {
        this.taskIdField = taskId;
        payload.put("taskId", taskId);
        return this;
    }

    public AgentMessageBuilder content(String content) {
        payload.put("content", content);
        return this;
    }

    public AgentMessageBuilder put(String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
        return this;
    }

    // ── SESSION_START ──

    public AgentMessageBuilder sessionStart(String content) {
        this.type = MessageType.SESSION_START;
        payload.put("content", content);
        return this;
    }

    // ── TEXT_COMPLETE ──

    public AgentMessageBuilder textChunk(String content) {
        this.type = MessageType.TEXT_CHUNK;
        payload.put("content", content);
        return this;
    }

    public AgentMessageBuilder textComplete(String content) {
        this.type = MessageType.TEXT_COMPLETE;
        payload.put("content", content);
        return this;
    }

    /**
     * 任务最终结果（带指标）
     */
    public AgentMessageBuilder result(String content) {
        this.type = MessageType.TEXT_COMPLETE;
        payload.put("content", content);
        payload.put("isResult", true);
        return this;
    }

    public AgentMessageBuilder metrics(BigDecimal costUsd, Long durationMs,
                                        Long inputTokens, Long outputTokens,
                                        Integer numTurns, String model) {
        if (costUsd != null) payload.put("costUsd", costUsd);
        if (durationMs != null) payload.put("durationMs", durationMs);
        if (inputTokens != null) payload.put("inputTokens", inputTokens);
        if (outputTokens != null) payload.put("outputTokens", outputTokens);
        if (numTurns != null) payload.put("numTurns", numTurns);
        if (model != null) payload.put("model", model);
        return this;
    }

    // ── TOOL_CALL_START —— 统一字段名 ──

    /**
     * 工具调用开始。
     * <p>
     * 统一字段名：toolCallId / toolName / arguments
     * （Codex 原字段 tool/input 映射到此标准）
     */
    public AgentMessageBuilder toolCallStart(String toolCallId, String toolName, Object arguments) {
        this.type = MessageType.TOOL_CALL_START;
        if (toolCallId != null) payload.put("toolCallId", toolCallId);
        payload.put("toolName", toolName);
        if (arguments != null) payload.put("arguments", arguments);
        return this;
    }

    // ── TOOL_CALL_RESULT —— 统一字段名 ──

    /**
     * 工具调用结果。
     * <p>
     * 统一字段名：toolCallId / toolName / data / success
     * （Codex 原字段 tool/output/isError 映射到此标准）
     */
    public AgentMessageBuilder toolCallResult(String toolCallId, String toolName,
                                               Object data, boolean success) {
        this.type = MessageType.TOOL_CALL_RESULT;
        if (toolCallId != null) payload.put("toolCallId", toolCallId);
        payload.put("toolName", toolName);
        payload.put("data", data);
        payload.put("success", success);
        return this;
    }

    // ── ERROR ──

    public AgentMessageBuilder error(String content) {
        this.type = MessageType.ERROR;
        payload.put("content", content);
        return this;
    }

    // ── STATE_SYNC ──

    public AgentMessageBuilder stateSync(String content, String subtype) {
        this.type = MessageType.STATE_SYNC;
        payload.put("content", content);
        if (subtype != null) payload.put("subtype", subtype);
        return this;
    }

    // ── CONFIRMATION_REQUEST ──

    public AgentMessageBuilder confirmationRequest(String permissionId) {
        this.type = MessageType.CONFIRMATION_REQUEST;
        payload.put("permissionId", permissionId);
        return this;
    }

    // ── CHECKPOINT ──

    public AgentMessageBuilder checkpoint(String checkpointId) {
        this.type = MessageType.CHECKPOINT;
        payload.put("checkpointId", checkpointId);
        return this;
    }

    // ── 构建 ──

    /**
     * 构建 AgentMessage。
     *
     * @throws IllegalStateException 如果未设置 MessageType
     */
    public AgentMessage build() {
        if (type == null) {
            throw new IllegalStateException("MessageType must be set before build()");
        }
        AgentMessage msg = AgentMessage.of(sessionId, agentId, type, new LinkedHashMap<>(payload));
        msg.setTaskId(taskIdField);
        return msg;
    }

    /**
     * 直接使用指定 type 构建（用于 Relay 已确定 type 的场景）
     */
    public AgentMessage build(MessageType overrideType) {
        AgentMessage msg = AgentMessage.of(sessionId, agentId, overrideType, new LinkedHashMap<>(payload));
        msg.setTaskId(taskIdField);
        return msg;
    }
}
