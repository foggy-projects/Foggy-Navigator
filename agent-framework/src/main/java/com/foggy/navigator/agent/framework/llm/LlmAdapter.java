package com.foggy.navigator.agent.framework.llm;

/**
 * LLM适配器接口
 * 隔离底层框架变化，便于切换实现
 */
public interface LlmAdapter {

    /**
     * 同步调用LLM
     */
    LlmResponse chat(LlmRequest request);

    /**
     * 流式调用LLM
     */
    void chatStream(LlmRequest request, LlmStreamHandler handler);

    /**
     * 获取适配器名称
     */
    String getName();

    /**
     * 检查是否支持指定模型
     */
    boolean supportsModel(String model);
}
