package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

/**
 * SSH 连接表单
 * 若 directoryId 有值且目录已保存 SSH 凭证，host/username/password 可省略，从目录配置自动填充。
 */
@Data
public class SshConnectForm {
    private String workerId;
    /** 可选 — 传入后从目录配置读取 SSH 凭证 */
    private String directoryId;
    /** SSH 主机，留空则使用 Worker hostname */
    private String host;
    private int port = 22;
    private String username;
    private String password;
    private int cols = 80;
    private int rows = 24;
}
