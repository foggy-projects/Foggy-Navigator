package com.foggy.navigator.auth.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 认证模块自动配置
 * 其他模块引入 user-auth-module 依赖后，会自动扫描认证相关的组件
 */
@AutoConfiguration
@ComponentScan(basePackages = {
    "com.foggy.navigator.auth.*"
})
@EntityScan(basePackages = {
    "com.foggy.navigator.auth.entity"
})
@EnableJpaRepositories(basePackages = {
    "com.foggy.navigator.auth.repository"
})
public class AuthAutoConfiguration {
}
