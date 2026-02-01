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
    @Builder.Default
    private double temperature = 0.7;
    private String systemPrompt;
}
