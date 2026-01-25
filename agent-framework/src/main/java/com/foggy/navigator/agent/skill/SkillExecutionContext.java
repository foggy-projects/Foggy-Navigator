package com.foggy.navigator.agent.skill;

import com.foggy.navigator.agent.session.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Skill执行上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillExecutionContext {
    private String sessionId;
    private String userId;
    private String userMessage;
    private List<Message> recentMessages;
    private Map<String, Object> variables;
}
