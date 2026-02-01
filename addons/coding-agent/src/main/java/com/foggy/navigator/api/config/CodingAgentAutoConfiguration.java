package com.foggy.navigator.api.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Coding Agent 模块自动配置
 * 提供 OpenHands 集成、会话管理、容器管理等功能
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.api.controller",
        "com.foggy.navigator.api.service",
        "com.foggy.navigator.api.repository",
        "com.foggy.navigator.api.listener",
        "com.foggy.navigator.api.sse",
        "com.foggy.navigator.foundation",
        "com.foggy.navigator.api.model"
})
@EntityScan(basePackages = {
        "com.foggy.navigator.api.model.entity"
})
@EnableJpaRepositories(basePackages = {
        "com.foggy.navigator.api.repository"
})
public class CodingAgentAutoConfiguration {
}
