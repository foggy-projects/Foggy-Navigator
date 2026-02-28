package com.foggy.navigator.task.assistant.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Task Assistant 模块自动配置
 */
@AutoConfiguration
@EnableScheduling
@ComponentScan(basePackages = {
        "com.foggy.navigator.task.assistant.service",
        "com.foggy.navigator.task.assistant.controller",
        "com.foggy.navigator.task.assistant.bridge"
})
@EntityScan(basePackages = {
        "com.foggy.navigator.task.assistant.entity"
})
@EnableJpaRepositories(basePackages = {"com.foggy.navigator.task.assistant.repository"})
public class TaskAssistantAutoConfiguration {
}
