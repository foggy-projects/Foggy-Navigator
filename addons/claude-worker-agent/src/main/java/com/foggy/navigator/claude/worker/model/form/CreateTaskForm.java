package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 创建任务表单
 */
@Data
public class CreateTaskForm {
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
}
