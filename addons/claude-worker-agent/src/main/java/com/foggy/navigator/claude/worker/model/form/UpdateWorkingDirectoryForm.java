package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 更新工作目录表单
 */
@Data
public class UpdateWorkingDirectoryForm {
    private String projectName;
    private String path;
    private String agentTeamsConfig;
}
