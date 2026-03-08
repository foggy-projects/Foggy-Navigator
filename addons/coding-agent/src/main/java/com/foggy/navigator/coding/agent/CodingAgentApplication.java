package com.foggy.navigator.coding.agent;

import com.foggy.navigator.auth.config.AuthAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Coding Agent 应用启动类
 */
@SpringBootApplication(exclude = {
        HttpClientAutoConfiguration.class
})
@Import(AuthAutoConfiguration.class)
@ComponentScan(basePackages = {
        "com.foggy.navigator.coding.agent.*"
})
@EntityScan(basePackages = {
        "com.foggy.navigator.coding.agent.api.model",
        "com.foggy.navigator.common.entity"
})
@EnableJpaRepositories(basePackages = {
        "com.foggy.navigator.coding.agent.api.repository"
})
public class CodingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodingAgentApplication.class, args);
    }

    /**
     * 生产环境配置 - 排除数据源自动配置
     */
    @Configuration
    @ConditionalOnProperty(name = "spring.profiles.active", havingValue = "prod", matchIfMissing = true)
    static class ProductionConfig {
        // 在生产环境中，如果不需要数据库，可以在这里添加额外配置
    }
}
