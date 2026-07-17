package com.foggy.navigator.codex.worker.model.command;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Codex provider 内部任务创建命令。
 *
 * <p>通用 HTTP 和任务分派边界使用统一请求模型；本命令只承载 Codex
 * 执行链所需的 provider 专属上下文，不作为 Controller 请求契约。</p>
 */
@Data
public class CodexTaskCreateCommand {
    private String agentId;
    private String workerId;
    private String prompt;
    private String cwd;
    private String directoryId;
    private String model;
    private Integer maxTurns;
    /** Worker 客户端期望的序列化图片载荷。 */
    private String images;
    private List<Map<String, Object>> attachments;
    private String codexThreadId;
    private String modelConfigId;
    private String sessionId;
    private String contextId;
    private String providerType;
    private String codexHomeKey;
    private String privateAccountId;
    private String developerInstructions;
    private Map<String, Object> businessRuntimeContext;
    private Map<String, Object> outputSchema;
    private Map<String, Object> codexConfig;
    private String sandboxMode;
    private String approvalPolicy;
    private Boolean networkAccessEnabled;
    private String webSearchMode;
    private List<String> additionalDirectories;
    private boolean initializeRuntimeAffinity;
}
