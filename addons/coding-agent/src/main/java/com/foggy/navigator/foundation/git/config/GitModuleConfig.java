package com.foggy.navigator.foundation.git.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Git 模块配置
 */
@Configuration
public class GitModuleConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
