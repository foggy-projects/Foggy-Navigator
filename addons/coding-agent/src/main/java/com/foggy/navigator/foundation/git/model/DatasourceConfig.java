package com.foggy.navigator.foundation.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据源配置
 * TODO: 等待验证服务提供详细的数据源配置格式
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasourceConfig {

    /**
     * JDBC URL
     */
    private String url;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 驱动类名
     */
    private String driverClassName;
}
