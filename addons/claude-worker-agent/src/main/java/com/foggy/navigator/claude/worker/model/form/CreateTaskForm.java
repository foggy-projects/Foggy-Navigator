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
}
