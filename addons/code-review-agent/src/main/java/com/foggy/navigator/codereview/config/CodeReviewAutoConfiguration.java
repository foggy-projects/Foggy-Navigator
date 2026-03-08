package com.foggy.navigator.codereview.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

/**
 * Code Review Agent 模块自动配置
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.codereview.service",
        "com.foggy.navigator.codereview.controller"
})
@EntityScan(basePackages = {
        "com.foggy.navigator.codereview.model.entity"
})
@EnableJpaRepositories(basePackages = {"com.foggy.navigator.codereview.repository"})
@EnableAsync
public class CodeReviewAutoConfiguration {

    /**
     * 代码审核专用线程池，避免阻塞主线程和共享线程池
     */
    @Bean("codeReviewExecutor")
    public Executor codeReviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("code-review-");
        executor.initialize();
        return executor;
    }

    /**
     * GitLab API 调用用 RestTemplate
     */
    @Bean("codeReviewRestTemplate")
    public RestTemplate codeReviewRestTemplate() {
        return new RestTemplate();
    }
}
