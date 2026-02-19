package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * 创建工作目录表单
 */
@Data
public class CreateWorkingDirectoryForm {
    private String workerId;
    private String projectName;
    private String path;
    /** optional, default "STANDARD". Values: STANDARD | PROJECT */
    private String directoryType;
    /** optional, 关联到 PROJECT 类型目录 */
    private String parentProjectId;
}
