package com.foggy.navigator.gemini.worker.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Gemini Worker Agent 模块自动配置
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.gemini.worker.service",
        "com.foggy.navigator.gemini.worker.controller",
        "com.foggy.navigator.gemini.worker.client",
        "com.foggy.navigator.gemini.worker.spi",
        "com.foggy.navigator.gemini.worker.adapter"
})
@EntityScan(basePackages = {
        "com.foggy.navigator.gemini.worker.model.entity",
        "com.foggy.navigator.common.entity"
})
@EnableJpaRepositories(basePackages = {
        "com.foggy.navigator.gemini.worker.repository",
        "com.foggy.navigator.common.repository"
})
public class GeminiWorkerAutoConfiguration {
}
