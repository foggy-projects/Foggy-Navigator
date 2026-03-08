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
}
