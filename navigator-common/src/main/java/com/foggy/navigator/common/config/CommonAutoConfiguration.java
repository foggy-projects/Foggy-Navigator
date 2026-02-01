package com.foggy.navigator.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * Common 模块自动配置
 * 提供通用的实体、DTO、枚举等
 */
@AutoConfiguration
@EntityScan(basePackages = {
    "com.foggy.navigator.common.entity"
})
public class CommonAutoConfiguration {
}
