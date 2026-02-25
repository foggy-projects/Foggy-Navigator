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
    /** 仅 PROJECT 类型可编辑 */
    private String projectTaskPrompt;
    /** 可修改归属 PROJECT */
    private String parentProjectId;
    /** SSH 用户名, "" 清除, null 不改 */
    private String sshUsername;
    /** SSH 密码（明文提交, "" 清除, null 不改） */
    private String sshPassword;
    /** SSH 端口, null 不改 */
    private Integer sshPort;
    /** 默认认证模式, "" 清除, null 不改 */
    private String defaultAuthMode;
    /** 默认认证 Token（明文提交, "" 清除, null 不改） */
    private String defaultAuthToken;
    /** 默认 Base URL */
    private String defaultBaseUrl;
}
