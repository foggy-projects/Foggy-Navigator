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
    private final String agentTeamsJson;
    /** Base64-encoded image attachments JSON: [{name, data, mimeType}] */
    private final String images;
    // Per-conversation auth (decrypted, from ConversationConfig)
    private final String apiKey;
    private final String authToken;
    private final String baseUrl;
    /** Permission mode: bypassPermissions | acceptEdits | default */
    private final String permissionMode;
    /** Navigator platform API Key (plaintext) for injecting into CLI env */
    private final String navigatorApiKey;
    /** Navigator API base URL (e.g. http://host:8112) for CLI env injection */
    private final String navigatorApiBase;

    public ClaudeTaskStartEvent(Object source, String taskId, String sessionId,
                                 String workerId, String userId, String prompt,
                                 String cwd, String claudeSessionId) {
        this(source, taskId, sessionId, workerId, userId, prompt, cwd, claudeSessionId,
                null, null, null, null, null, null, null, null, null, null);
    }

    public ClaudeTaskStartEvent(Object source, String taskId, String sessionId,
                                 String workerId, String userId, String prompt,
                                 String cwd, String claudeSessionId,
                                 String model, Integer maxTurns, String agentTeamsJson,
                                 String apiKey, String authToken, String baseUrl) {
        this(source, taskId, sessionId, workerId, userId, prompt, cwd, claudeSessionId,
                model, maxTurns, agentTeamsJson, null, apiKey, authToken, baseUrl, null, null, null);
    }

    public ClaudeTaskStartEvent(Object source, String taskId, String sessionId,
                                 String workerId, String userId, String prompt,
                                 String cwd, String claudeSessionId,
                                 String model, Integer maxTurns, String agentTeamsJson,
                                 String images,
                                 String apiKey, String authToken, String baseUrl) {
        this(source, taskId, sessionId, workerId, userId, prompt, cwd, claudeSessionId,
                model, maxTurns, agentTeamsJson, images, apiKey, authToken, baseUrl, null, null, null);
    }

    public ClaudeTaskStartEvent(Object source, String taskId, String sessionId,
                                 String workerId, String userId, String prompt,
                                 String cwd, String claudeSessionId,
                                 String model, Integer maxTurns, String agentTeamsJson,
                                 String images,
                                 String apiKey, String authToken, String baseUrl,
                                 String permissionMode) {
        this(source, taskId, sessionId, workerId, userId, prompt, cwd, claudeSessionId,
                model, maxTurns, agentTeamsJson, images, apiKey, authToken, baseUrl,
                permissionMode, null, null);
    }

    public ClaudeTaskStartEvent(Object source, String taskId, String sessionId,
                                 String workerId, String userId, String prompt,
                                 String cwd, String claudeSessionId,
                                 String model, Integer maxTurns, String agentTeamsJson,
                                 String images,
                                 String apiKey, String authToken, String baseUrl,
                                 String permissionMode, String navigatorApiKey) {
        this(source, taskId, sessionId, workerId, userId, prompt, cwd, claudeSessionId,
                model, maxTurns, agentTeamsJson, images, apiKey, authToken, baseUrl,
                permissionMode, navigatorApiKey, null);
    }

    public ClaudeTaskStartEvent(Object source, String taskId, String sessionId,
                                 String workerId, String userId, String prompt,
                                 String cwd, String claudeSessionId,
                                 String model, Integer maxTurns, String agentTeamsJson,
                                 String images,
                                 String apiKey, String authToken, String baseUrl,
                                 String permissionMode, String navigatorApiKey,
                                 String navigatorApiBase) {
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
        this.agentTeamsJson = agentTeamsJson;
        this.images = images;
        this.apiKey = apiKey;
        this.authToken = authToken;
        this.baseUrl = baseUrl;
        this.permissionMode = permissionMode;
        this.navigatorApiKey = navigatorApiKey;
        this.navigatorApiBase = navigatorApiBase;
    }
}
