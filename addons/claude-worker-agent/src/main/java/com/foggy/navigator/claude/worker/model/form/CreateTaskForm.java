package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 创建任务表单
 *
 * @deprecated 使用 {@link com.foggy.navigator.session.service.TaskDispatchRequest} 替代。
 *             TaskDispatchRequest 是 Agent 无关的统一任务创建请求。
 */
@Deprecated(since = "unified-task-dispatch-refactor")
@Data
public class CreateTaskForm {
    /** 逻辑 CodingAgent ID（可选，缺省时回落到 session 已绑定 agent 或 provider） */
    private String agentId;
    private String workerId;
    private String prompt;
    private String cwd;
    private String directoryId;
    private String model;
    private Integer maxTurns;
    /** Agent Teams JSON (直接传入或由 directoryId 解析) */
    private String agentTeamsJson;
    /** Agent Teams 命名配置 ID（从命名配置中选择） */
    private String agentTeamsConfigId;
    /** Base64-encoded image attachments JSON: [{name, data, mimeType}] */
    private String images;
    /** Permission mode: bypassPermissions | acceptEdits | default */
    private String permissionMode;
    /** 平台 LLM 模型配置 ID，用于从平台配置中获取 apiKey + baseUrl */
    private String modelConfigId;
    /** A2A 多轮会话标识（contextId），持久化到 task entity 供轮询 API 返回 */
    private String contextId;
}
