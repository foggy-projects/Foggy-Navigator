package com.foggy.navigator.claude.worker.model.form;

import com.foggy.navigator.common.model.CodexConfig;
import lombok.Data;

/**
 * Worker 注册表单
 */
@Data
public class RegisterWorkerForm {
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
    /** Codex 配置（可选，含 baseUrl/authToken/model） */
    private CodexConfig codexConfig;
}
