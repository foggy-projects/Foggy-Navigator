package com.foggy.navigator.codex.worker.model.form;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建 Codex 任务表单
 *
 * @deprecated 使用 {@link com.foggy.navigator.session.service.TaskDispatchRequest} 替代。
 *             TaskDispatchRequest 是 Agent 无关的统一任务创建请求。
 */
@Deprecated(since = "unified-task-dispatch-refactor")
@Data
public class CreateCodexTaskForm {
    /** 前端解析的 CodingAgent 实体 ID（可选，用于 session 绑定和取消路由） */
    private String agentId;
    private String workerId;
    private String prompt;
    private String cwd;
    private String directoryId;
    private String model;
    private Integer maxTurns;
    /** Base64-encoded image attachments JSON: [{name, data, mime_type}] */
    private String images;
    /** 上游已上传附件元数据和 URL */
    private List<Map<String, Object>> attachments;
    /** Codex SDK thread ID（非空则恢复已有会话） */
    private String codexThreadId;
    /** 平台 LLM 模型配置 ID，用于从平台配置中获取 apiKey */
    private String modelConfigId;
    /** Navigator 平台 session ID（非空则复用已有会话，由 ContextResolvingA2aAgent 传入） */
    private String sessionId;
    /** OpenAPI/A2A 多轮上下文 ID，用于统一任务投影诊断 */
    private String contextId;
    /** Effective TaskQueryProvider route; defaults to codex-worker when omitted. */
    private String providerType;
    /** CodexBiz: actor/account scoped CODEX_HOME logical key; worker resolves it under CODEX_BIZ_HOME_ROOT. */
    private String codexHomeKey;
    /** Codex SDK developer_instructions config, used for account/task contract injection. */
    private String developerInstructions;
    /** Codex SDK turn outputSchema. */
    private Map<String, Object> outputSchema;
    /** Additional Codex SDK config overrides. */
    private Map<String, Object> codexConfig;
    /** Codex SDK sandboxMode override. */
    private String sandboxMode;
    /** Codex SDK approvalPolicy override. */
    private String approvalPolicy;
    /** Codex SDK networkAccessEnabled override. */
    private Boolean networkAccessEnabled;
    /** Codex SDK webSearchMode override. */
    private String webSearchMode;
    /** Codex SDK additionalDirectories override. */
    private List<String> additionalDirectories;
}
