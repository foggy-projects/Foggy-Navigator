package com.foggy.navigator.common.form;

import lombok.Data;

/**
 * MongoDB数据源信息
 */
@Data
public class MongoDatasourceInfo {
    /**
     * 主机地址（支持多个，逗号分隔）
     */
    private String hosts;

    /**
     * 端口号
     */
    private Integer port;

    /**
     * 数据库名称
     */
    private String database;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 认证数据库
     */
    private String authDatabase;

    /**
     * 连接字符串（可选）
     */
    private String connectionString;
}
