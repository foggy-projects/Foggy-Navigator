package com.foggy.navigator.session.service;

import lombok.Builder;
import lombok.Data;

import java.util.List;

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
     * 目标执行 Provider（claude-worker / codex-worker）。
     * <p>
     * @deprecated 前端不再需要显式传递此字段。providerType 由后端从 modelConfigId 推导。
     *             保留字段仅为 Open API 向后兼容。
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

    /** Agent Teams 配置 ID */
    private String agentTeamsConfigId;

    /** Agent Teams JSON */
    private String agentTeamsJson;

    /** A2A 多轮上下文 ID */
    private String contextId;

    /** 是否为 resume 操作 */
    private boolean resume;
}
