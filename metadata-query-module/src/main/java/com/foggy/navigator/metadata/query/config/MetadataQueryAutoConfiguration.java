package com.foggy.navigator.metadata.query.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Metadata Query 模块自动配置
 * 提供元数据查询功能
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.metadata.controller",
        "com.foggy.navigator.metadata.service",
        "com.foggy.navigator.metadata.config"
})
public class MetadataQueryAutoConfiguration {
}
