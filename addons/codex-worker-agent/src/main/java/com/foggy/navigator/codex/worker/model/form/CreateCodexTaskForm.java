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
}
