package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 恢复任务表单
 */
@Data
public class ResumeTaskForm {
    /** 逻辑 CodingAgent ID（可选，缺省时回落到已绑定 session agent） */
    private String agentId;
    private String workerId;
    private String claudeSessionId;
    private String prompt;
    private String cwd;
    private String directoryId;
    /** 复用已有 Navigator Session（per-conversation 模式） */
    private String sessionId;
    private String model;
    private Integer maxTurns;
    /** Agent Teams JSON (直接传入或由 directoryId 解析) */
    private String agentTeamsJson;
    /** Agent Teams 命名配置 ID（从命名配置中选择） */
    private String agentTeamsConfigId;
    /** Base64-encoded image attachments JSON: [{name, data, mime_type}] */
    private String images;
    /** 上游已上传附件元数据和 URL */
    private List<Map<String, Object>> attachments;
    /** Permission mode: bypassPermissions | acceptEdits | default */
    private String permissionMode;
    /** 平台 LLM 模型配置 ID，用于从平台配置中获取 apiKey + baseUrl */
    private String modelConfigId;
}
