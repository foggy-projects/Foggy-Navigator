package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 注册 Agent 表单
 */
@Data
public class RegisterAgentForm {
    private String name;
    private String description;
    private String agentType;
    private String workerId;
    private String defaultDirectoryId;
    private String skills;
    private String defaultBranch;
}
