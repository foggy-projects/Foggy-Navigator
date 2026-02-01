package com.foggy.navigator.coding.agent.api.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;

/**
 * Coding Agent 模块自动配置
 * 提供 OpenHands 集成、会话管理、容器管理等功能
 *
 * 注意: @EnableJpaRepositories 已在 CodingAgentApplication 中配置，
 * 此处不重复声明以避免 BeanDefinitionOverrideException
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.coding.agent.api.*"
})
@EntityScan(basePackages = {
        "com.foggy.navigator.coding.agent.api.model.entity"
})
public class CodingAgentAutoConfiguration {
}
