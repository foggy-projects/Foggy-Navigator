package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * SSH 连接表单
 * host/username/password 可省略，从 Worker 配置自动填充。
 * directoryId 有值时自动设置 cwd。
 */
@Data
public class SshConnectForm {
    private String workerId;
    /** 可选 — 传入后从目录读取 cwd */
    private String directoryId;
    /** SSH 主机，留空则使用 Worker hostname */
    private String host;
    private int port = 22;
    private String username;
    private String password;
    private int cols = 80;
    private int rows = 24;
}
