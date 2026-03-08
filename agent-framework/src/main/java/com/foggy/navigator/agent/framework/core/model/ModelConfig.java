package com.foggy.navigator.agent.framework.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfig {
    private String provider;  // openai / anthropic / ollama
    private String model;
    private String category;  // GENERAL / CODING / REASONING / VISION
    @Builder.Default
    private double temperature = 0.7;
    private String systemPrompt;

    /**
     * 上下文窗口 token 预算（仅用于历史消息选择）
     * 默认 8000 tokens，留出空间给 system prompt + LLM 回复
     */
    @Builder.Default
    private int maxContextTokens = 8000;
}
