package com.foggy.navigator.claude.worker.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

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

    /**
     * A2A 异步任务执行线程池
     * 用于 ClaudeWorkerA2aAgent.sendTask 的后台异步执行
     */
    @Bean("a2aAsyncExecutor")
    public Executor a2aAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("a2a-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
