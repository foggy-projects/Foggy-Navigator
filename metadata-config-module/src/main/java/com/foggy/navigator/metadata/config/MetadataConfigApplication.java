package com.foggy.navigator.metadata.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * 配置管理服务启动类
 */
@SpringBootApplication
@EntityScan(basePackages = "com.foggy.navigator.common.entity")
public class MetadataConfigApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetadataConfigApplication.class, args);
    }
}
