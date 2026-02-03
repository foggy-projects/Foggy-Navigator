package com.foggy.navigator.metadata.query.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 配置管理服务启动类
 * 排除 MongoDB 自动配置，因为项目在运行时动态创建 MongoTemplate
 */
@SpringBootApplication(exclude = {
    MongoAutoConfiguration.class,
    MongoDataAutoConfiguration.class
})
@ComponentScan(basePackages = {
    "com.foggy.navigator.metadata.query.config",
    "com.foggy.navigator.auth"  // 扫描认证模块
})
@EntityScan(basePackages = "com.foggy.navigator.common.entity")
@EnableJpaRepositories(basePackages = {
    "com.foggy.navigator.metadata.query.config.repository"
    // 认证模块的 Repository 由 AuthAutoConfiguration 自动配置
})
public class MetadataConfigApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetadataConfigApplication.class, args);
    }
}
