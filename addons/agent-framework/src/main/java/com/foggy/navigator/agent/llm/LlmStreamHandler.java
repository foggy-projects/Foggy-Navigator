package com.foggy.navigator.agent.llm;

/**
 * LLM流式响应处理器
 */
public interface LlmStreamHandler {

    /**
     * 接收文本片段
     */
    void onText(String text);

    /**
     * 接收工具调用
     */
    void onToolCall(ToolCall toolCall);

    /**
     * 完成
     */
    void onComplete(LlmResponse response);

    /**
     * 错误
     */
    void onError(Throwable error);
}
