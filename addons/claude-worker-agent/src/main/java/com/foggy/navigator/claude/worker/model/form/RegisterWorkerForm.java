package com.foggy.navigator.claude.worker.model.form;

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
}
