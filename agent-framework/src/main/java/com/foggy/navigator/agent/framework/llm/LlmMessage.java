package com.foggy.navigator.agent.framework.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMessage {
    private String role;  // system / user / assistant / tool
    private String content;
    private String toolCallId;
    private List<ToolCall> toolCalls;

    public static LlmMessage system(String content) {
        return LlmMessage.builder().role("system").content(content).build();
    }

    public static LlmMessage user(String content) {
        return LlmMessage.builder().role("user").content(content).build();
    }

    public static LlmMessage assistant(String content) {
        return LlmMessage.builder().role("assistant").content(content).build();
    }

    public static LlmMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return LlmMessage.builder()
                .role("assistant")
                .content(content)
                .toolCalls(toolCalls)
                .build();
    }

    public static LlmMessage tool(String toolCallId, String content) {
        return LlmMessage.builder().role("tool").toolCallId(toolCallId).content(content).build();
    }
}
