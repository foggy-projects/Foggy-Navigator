package com.foggy.navigator.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;

/**
 * Common 模块自动配置
 * 提供通用的实体、DTO、枚举等，以及公共组件（如 CredentialEncryptor）
 */
@AutoConfiguration
@ComponentScan(basePackages = {
    "com.foggy.navigator.common.security"
})
@EntityScan(basePackages = {
    "com.foggy.navigator.common.entity"
})
public class CommonAutoConfiguration {
}
