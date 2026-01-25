package com.foggy.navigator.agent.llm;

import com.foggy.navigator.agent.tool.ToolDefinition;
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
    @Builder.Default
    private double temperature = 0.7;
    private String systemPrompt;
    private List<LlmMessage> messages;
    private List<ToolDefinition> tools;
}
