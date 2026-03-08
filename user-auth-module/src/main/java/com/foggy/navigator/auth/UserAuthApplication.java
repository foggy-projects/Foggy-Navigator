package com.foggy.navigator.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * 用户认证服务启动类
 */
@SpringBootApplication
@EntityScan(basePackages = "com.foggy.navigator.common.entity")
public class UserAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserAuthApplication.class, args);
    }
}
