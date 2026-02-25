package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * SSH 连接表单
 */
@Data
public class SshConnectForm {
    private String workerId;
    private String host;
    private int port = 22;
    private String username;
    private String password;
    private int cols = 80;
    private int rows = 24;
}
