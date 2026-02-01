package com.foggy.navigator.metadata.query.config.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Metadata Config 模块自动配置
 * 提供元数据配置管理功能
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.metadata.config.controller",
        "com.foggy.navigator.metadata.config.service",
        "com.foggy.navigator.metadata.config.repository",
        "com.foggy.navigator.metadata.config.configuration"
})
@EnableJpaRepositories(basePackages = {
        "com.foggy.navigator.metadata.config.repository"
})
public class MetadataConfigAutoConfiguration {
}
