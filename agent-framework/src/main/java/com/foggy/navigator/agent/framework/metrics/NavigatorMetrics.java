package com.foggy.navigator.agent.framework.metrics;

/**
 * Micrometer 指标名称和 Tag key 常量
 */
public final class NavigatorMetrics {

    private NavigatorMetrics() {}

    // === LLM 指标 ===
    public static final String LLM_CALLS = "navigator.llm.calls";
    public static final String LLM_DURATION = "navigator.llm.duration";
    public static final String LLM_TOKENS_INPUT = "navigator.llm.tokens.input";
    public static final String LLM_TOKENS_OUTPUT = "navigator.llm.tokens.output";
    public static final String LLM_CIRCUIT_BREAKER_STATE = "navigator.llm.circuit_breaker.state";
    public static final String LLM_CIRCUIT_BREAKER_FAILURES = "navigator.llm.circuit_breaker.failures";

    // === Tool 指标 ===
    public static final String TOOL_EXECUTIONS = "navigator.tool.executions";
    public static final String TOOL_DURATION = "navigator.tool.duration";

    // === Agent 指标 ===
    public static final String AGENT_INVOCATIONS = "navigator.agent.invocations";
    public static final String AGENT_DURATION = "navigator.agent.duration";
    public static final String AGENT_ITERATIONS = "navigator.agent.iterations";

    // === SSE 指标 ===
    public static final String SSE_ACTIVE_CONNECTIONS = "navigator.sse.active_connections";

    // === Tag keys ===
    public static final String TAG_MODEL = "model";
    public static final String TAG_SUCCESS = "success";
    public static final String TAG_FINISH_REASON = "finish_reason";
    public static final String TAG_TOOL_NAME = "tool_name";
    public static final String TAG_AGENT_ID = "agent_id";
}
