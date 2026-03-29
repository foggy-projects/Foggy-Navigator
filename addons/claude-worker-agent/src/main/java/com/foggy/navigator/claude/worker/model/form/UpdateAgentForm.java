package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 更新 Agent 表单
 */
@Data
public class UpdateAgentForm {
    private String name;
    private String description;
    private String skills;
    private String defaultBranch;
    private String defaultDirectoryId;
    private String projectSummary;
    /** 默认 LLM 模型配置 ID（空串=清除） */
    private String defaultModelConfigId;
}
