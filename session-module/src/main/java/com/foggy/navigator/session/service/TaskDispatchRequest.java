package com.foggy.navigator.session.service;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 统一任务创建请求 —— 屏蔽 Claude / Codex 差异，
 * 由 Controller 或 OpenAPI 构造，交给 TaskDispatchFacade 处理。
 */
@Data
@Builder
public class TaskDispatchRequest {

    /** 目标逻辑 Agent ID */
    private String agentId;

    /**
     * 目标执行 Provider（如 claude-worker / codex-worker / codex-biz-worker）。
     * <p>
     * @deprecated 常规前端任务优先由后端从 modelConfigId 推导；OpenAPI 或独立执行 route
     *             仍可显式传递 providerType。
     */
    @Deprecated
    private String providerType;

    /** 平台会话 ID（null 表示新建会话） */
    private String sessionId;

    /** Worker ID */
    private String workerId;

    /** 任务提示词 */
    private String prompt;

    /** 工作目录 */
    private String cwd;

    /** 目录 ID */
    private String directoryId;

    /** 模型名称 */
    private String model;

    /** 模型配置 ID（用于认证/模型配置，并可辅助推导 Provider） */
    private String modelConfigId;

    /** 最大 turn 数 */
    private Integer maxTurns;

    /** 权限模式 */
    private String permissionMode;

    /** 图片附件（Base64 列表） */
    private List<String> images;

    /** 上游已上传附件元数据和 URL */
    private List<Map<String, Object>> attachments;

    /** Agent Teams 配置 ID */
    private String agentTeamsConfigId;

    /** Agent Teams JSON */
    private String agentTeamsJson;

    /** A2A 多轮上下文 ID */
    private String contextId;

    /** Provider-specific structured context, e.g. LangGraph FSScript execution hints */
    private Map<String, Object> context;

    /** Additional A2A metadata preserved by complex Agent submit paths. */
    private Map<String, Object> metadata;

    /** 上下文别名（用于按别名复用会话） */
    private String contextAlias;

    /** 是否为 resume 操作 */
    private boolean resume;
}
