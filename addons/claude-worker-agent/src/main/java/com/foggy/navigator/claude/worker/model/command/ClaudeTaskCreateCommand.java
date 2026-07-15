package com.foggy.navigator.claude.worker.model.command;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Claude provider 内部创建命令。
 *
 * <p>通用 HTTP 和分派边界使用 TaskDispatchRequest；本命令只承载
 * Claude 执行链所需的 provider 专属上下文，不作为 Controller 请求契约。</p>
 */
@Data
public class ClaudeTaskCreateCommand {
    private String agentId;
    private String workerId;
    private String prompt;
    private String cwd;
    private String directoryId;
    private String model;
    private Integer maxTurns;
    private String agentTeamsJson;
    private String agentTeamsConfigId;
    /** Worker 客户端期望的序列化图片载荷。 */
    private String images;
    private List<Map<String, Object>> attachments;
    private String permissionMode;
    private String modelConfigId;
    private String contextId;
    private String claudeSessionId;
    private String sessionId;
}
