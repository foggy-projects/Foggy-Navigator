package com.foggy.navigator.agent.framework.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Agent Framework 模块自动配置
 * 提供智能体核心功能，包括会话管理、技能管理、工具管理等
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.agent.framework.*"
})
public class AgentFrameworkAutoConfiguration {
}
