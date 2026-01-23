package com.foggy.navigator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Coding Agent 应用启动类
 */
@SpringBootApplication(exclude = {
        HttpClientAutoConfiguration.class
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
