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

    /** 目标 Agent ID（显式指定，优先于 modelConfigId 推导） */
    private String agentId;

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

    /** 模型配置 ID（兼容旧前端，用于推导 Agent） */
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

    /** Codex Thread ID（续接 Codex 会话） */
    private String codexThreadId;

    /** Claude Session ID（resume 时续接指定会话） */
    private String claudeSessionId;

    /** 是否为 resume 操作 */
    private boolean resume;
}
