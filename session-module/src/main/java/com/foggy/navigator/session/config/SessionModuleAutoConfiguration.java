package com.foggy.navigator.session.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Session Module 自动配置
 */
@AutoConfiguration(after = JpaRepositoriesAutoConfiguration.class)
@ComponentScan(basePackages = {
    "com.foggy.navigator.session.service",
    "com.foggy.navigator.session.controller",
    "com.foggy.navigator.session.sse",
    "com.foggy.navigator.session.event",
    "com.foggy.navigator.session.filter",
    "com.foggy.navigator.session.registry",
    "com.foggy.navigator.session.agent",
    "com.foggy.navigator.session.util",
    "com.foggy.navigator.session.lifecycle"
})
@EntityScan(basePackages = {
    "com.foggy.navigator.common.entity",
    "com.foggy.navigator.session.lifecycle.persistence"
})
@EnableJpaRepositories(basePackages = {
    "com.foggy.navigator.session.repository",
    "com.foggy.navigator.session.lifecycle.repository"
})
@EnableConfigurationProperties({
        ErrorDiagnosticProperties.class,
        com.foggy.navigator.session.lifecycle.LifecycleActivationProperties.class
})
@EnableAsync
@EnableScheduling
public class SessionModuleAutoConfiguration {

    @Bean
    public com.foggy.navigator.session.lifecycle.TerminalCleanupPlanFactory
            terminalCleanupPlanFactory() {
        return new com.foggy.navigator.session.lifecycle.TerminalCleanupPlanFactory();
    }

    @Bean("sessionEventExecutor")
    public AsyncTaskExecutor sessionEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("session-event-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
