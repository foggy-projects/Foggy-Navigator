package com.foggy.navigator.auth.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 认证模块自动配置
 * 其他模块引入 user-auth-module 依赖后，会自动扫描认证相关的组件
 */
@Configuration
@ComponentScan(basePackages = {
    "com.foggy.navigator.auth.util",
    "com.foggy.navigator.auth.service",
    "com.foggy.navigator.auth.repository",
    "com.foggy.navigator.auth.interceptor",
    "com.foggy.navigator.auth.aspect",
    "com.foggy.navigator.auth.config"
})
public class AuthAutoConfiguration {
}
