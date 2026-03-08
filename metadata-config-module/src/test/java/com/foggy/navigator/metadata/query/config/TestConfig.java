package com.foggy.navigator.metadata.query.config;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableAutoConfiguration
@EntityScan(basePackages = "com.foggy.navigator.common.entity")
@EnableJpaRepositories(basePackages = "com.foggy.navigator.metadata.query.config.repository")
@ComponentScan(basePackages = {
        "com.foggy.navigator.metadata.query.config.service",
        "com.foggy.navigator.common.security"
})
class TestConfig {
}
