package com.foggy.navigator.agent.framework.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Skill执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillExecutionResult {
    private boolean success;
    private String response;
    private boolean shouldDelegate;
    private String targetAgentId;
    private Map<String, Object> contextData;
    private String errorMessage;

    public static SkillExecutionResult success(String response) {
        return SkillExecutionResult.builder()
                .success(true)
                .response(response)
                .build();
    }

    public static SkillExecutionResult delegate(String targetAgentId, Map<String, Object> contextData) {
        return SkillExecutionResult.builder()
                .success(true)
                .shouldDelegate(true)
                .targetAgentId(targetAgentId)
                .contextData(contextData)
                .build();
    }

    public static SkillExecutionResult error(String message) {
        return SkillExecutionResult.builder()
                .success(false)
                .errorMessage(message)
                .build();
    }
}
