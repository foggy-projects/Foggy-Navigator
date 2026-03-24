package com.foggy.navigator.codex.worker.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Codex Worker Agent 模块自动配置
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.codex.worker.service",
        "com.foggy.navigator.codex.worker.controller",
        "com.foggy.navigator.codex.worker.client",
        "com.foggy.navigator.codex.worker.spi",
        "com.foggy.navigator.codex.worker.adapter"
})
@EntityScan(basePackages = {
        "com.foggy.navigator.codex.worker.model.entity",
        "com.foggy.navigator.common.entity"
})
@EnableJpaRepositories(basePackages = {
        "com.foggy.navigator.codex.worker.repository",
        "com.foggy.navigator.common.repository"
})
public class CodexWorkerAutoConfiguration {
}
