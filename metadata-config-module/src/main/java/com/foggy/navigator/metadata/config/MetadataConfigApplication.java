package com.foggy.navigator.metadata.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;

/**
 * 配置管理服务启动类
 * 排除 MongoDB 自动配置，因为项目在运行时动态创建 MongoTemplate
 */
@SpringBootApplication(exclude = {
    MongoAutoConfiguration.class,
    MongoDataAutoConfiguration.class
})
@EntityScan(basePackages = "com.foggy.navigator.common.entity")
public class MetadataConfigApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetadataConfigApplication.class, args);
    }
}
