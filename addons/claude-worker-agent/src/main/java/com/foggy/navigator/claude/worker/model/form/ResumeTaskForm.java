package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 恢复任务表单
 */
@Data
public class ResumeTaskForm {
    private String workerId;
    private String claudeSessionId;
    private String prompt;
    private String cwd;
}
