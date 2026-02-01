package com.foggy.navigator.metadata.query;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 元数据查询模块主应用
 *
 * Foggy Dataset Model 通过 Spring Boot Auto-Configuration 自动启用
 */
@SpringBootApplication
public class MetadataQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetadataQueryApplication.class, args);
    }
}
