package com.foggy.navigator.agent.framework.llm;

import com.foggy.navigator.agent.framework.tool.ToolDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {
    private String model;
    private String apiKey;
    private String baseUrl;
    @Builder.Default
    private double temperature = 0.7;
    private String systemPrompt;
    private List<LlmMessage> messages;
    private List<ToolDefinition> tools;

    @Builder.Default
    private int timeoutSeconds = 60;
    @Builder.Default
    private int maxRetries = 2;
    @Builder.Default
    private long retryBaseDelayMs = 1000;
}
