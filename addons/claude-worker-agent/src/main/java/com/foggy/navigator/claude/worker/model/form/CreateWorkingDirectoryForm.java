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
    /** optional, 默认认证模式 */
    private String defaultAuthMode;
    /** optional, 默认认证 Token（明文提交） */
    private String defaultAuthToken;
    /** optional, 默认 Base URL */
    private String defaultBaseUrl;
}
