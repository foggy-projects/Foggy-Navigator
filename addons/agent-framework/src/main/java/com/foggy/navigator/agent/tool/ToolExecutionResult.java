package com.foggy.navigator.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {
    private boolean success;
    private Object data;
    private String errorMessage;
    private String errorCode;
    private long executionTimeMs;

    public static ToolExecutionResult success(Object data) {
        return ToolExecutionResult.builder()
                .success(true)
                .data(data)
                .build();
    }

    public static ToolExecutionResult error(String message) {
        return ToolExecutionResult.builder()
                .success(false)
                .errorMessage(message)
                .build();
    }

    public static ToolExecutionResult error(String code, String message) {
        return ToolExecutionResult.builder()
                .success(false)
                .errorCode(code)
                .errorMessage(message)
                .build();
    }
}
