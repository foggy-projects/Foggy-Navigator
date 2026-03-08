package com.foggy.navigator.claude.worker.model.form;

import com.foggy.navigator.common.model.CodexConfig;
import lombok.Data;

/**
 * Worker 更新表单
 */
@Data
public class UpdateWorkerForm {
    private String name;
    private String baseUrl;
    private String authToken;
    private String authMode;
    private String sshUsername;
    private Integer sshPort;
    private String sshPassword;
    private String codeServerPublicUrl;
    private String codeServerInternalUrl;
    private String codeServerPassword;
    private String codeServerFolderPrefix;
    /** Codex 配置（可选，含 baseUrl/authToken/model；null=不修改，空对象=清除） */
    private CodexConfig codexConfig;
}
