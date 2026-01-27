package com.foggy.navigator.common.form;

import lombok.Data;

/**
 * JDBC数据源信息
 */
@Data
public class JdbcDatasourceInfo {
    /**
     * 数据库类型：MySQL, PostgreSQL, Oracle, SQL Server
     */
    private String dbType;

    /**
     * 主机地址
     */
    private String host;

    /**
     * 端口号
     */
    private Integer port;

    /**
     * 数据库名称
     */
    private String databaseName;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码（明文，后端加密存储）
     */
    private String password;

    /**
     * JDBC URL（可选，如果提供则优先使用）
     */
    private String jdbcUrl;

    /**
     * 额外参数（如 useSSL=false&serverTimezone=UTC）
     */
    private String extraParams;
}
