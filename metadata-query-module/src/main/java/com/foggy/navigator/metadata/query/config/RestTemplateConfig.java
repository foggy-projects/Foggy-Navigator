package com.foggy.navigator.metadata.query.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置
 */
@Configuration
public class RestTemplateConfig {

    /** Default HTTP client for framework consumers that inject RestTemplate by type. */
    @Bean("metadataQueryRestTemplate")
    @Primary
    public RestTemplate metadataQueryRestTemplate() {
        return new RestTemplate();
    }
}
