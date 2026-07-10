package com.foggy.navigator.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Common 模块自动配置
 * 提供通用的实体、DTO、枚举等，以及公共组件（如 CredentialEncryptor）
 */
@AutoConfiguration
@ComponentScan(basePackages = {
    "com.foggy.navigator.common.security",
    "com.foggy.navigator.common.migration"
})
@EntityScan(basePackages = {
    "com.foggy.navigator.common.entity"
})
@EnableJpaRepositories(basePackages = {
    "com.foggy.navigator.common.repository"
})
public class CommonAutoConfiguration {
}
