package com.foggy.navigator.claude.worker.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Claude Worker Agent 模块自动配置
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.claude.worker.service",
        "com.foggy.navigator.claude.worker.controller",
        "com.foggy.navigator.claude.worker.client",
        "com.foggy.navigator.claude.worker.spi",
        "com.foggy.navigator.claude.worker.adapter",
        "com.foggy.navigator.claude.worker.websocket"
})
@EntityScan(basePackages = {
        "com.foggy.navigator.claude.worker.model.entity"
})
@EnableJpaRepositories(basePackages = {"com.foggy.navigator.claude.worker.repository"})
@EnableScheduling
public class ClaudeWorkerAutoConfiguration {
}
