package com.foggy.navigator.metadata.query.config.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Metadata Config 模块自动配置
 * 提供元数据配置管理功能
 *
 * 注意：
 * 1. 当作为库被其他模块引用时，此自动配置生效
 * 2. 当独立运行（使用 MetadataConfigApplication）时，主应用的配置优先
 *    需要在 application.yml 中配置 spring.main.allow-bean-definition-overriding=true
 */
@AutoConfiguration
@AutoConfigureAfter(JpaRepositoriesAutoConfiguration.class)
@ComponentScan(basePackages = {
        "com.foggy.navigator.metadata.query.config.service",
        "com.foggy.navigator.metadata.query.config.controller"
})
@EntityScan(basePackages = {
        "com.foggy.navigator.common.entity"
})
@EnableJpaRepositories(basePackages = {
        "com.foggy.navigator.metadata.query.config.repository"
})
public class MetadataConfigAutoConfiguration {
}
