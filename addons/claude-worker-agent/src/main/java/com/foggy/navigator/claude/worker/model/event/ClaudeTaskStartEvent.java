package com.foggy.navigator.claude.worker.model.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Claude 任务启动事件
 * 发布后由 WorkerStreamRelay 监听并开始消费 Worker SSE 流
 */
@Getter
public class ClaudeTaskStartEvent extends ApplicationEvent {

    private final String taskId;
    private final String sessionId;
    private final String workerId;
    private final String userId;
    private final String prompt;
    private final String cwd;
    private final String claudeSessionId;
    private final String model;
    private final Integer maxTurns;

    public ClaudeTaskStartEvent(Object source, String taskId, String sessionId,
                                 String workerId, String userId, String prompt,
                                 String cwd, String claudeSessionId) {
        this(source, taskId, sessionId, workerId, userId, prompt, cwd, claudeSessionId, null, null);
    }

    public ClaudeTaskStartEvent(Object source, String taskId, String sessionId,
                                 String workerId, String userId, String prompt,
                                 String cwd, String claudeSessionId,
                                 String model, Integer maxTurns) {
        super(source);
        this.taskId = taskId;
        this.sessionId = sessionId;
        this.workerId = workerId;
        this.userId = userId;
        this.prompt = prompt;
        this.cwd = cwd;
        this.claudeSessionId = claudeSessionId;
        this.model = model;
        this.maxTurns = maxTurns;
    }
}
