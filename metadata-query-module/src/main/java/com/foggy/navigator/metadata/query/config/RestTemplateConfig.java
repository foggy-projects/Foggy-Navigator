package com.foggy.navigator.metadata.query.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置
 */
@Configuration
public class RestTemplateConfig {

    /** HTTP client dedicated to metadata query requests. */
    @Bean("metadataQueryRestTemplate")
    public RestTemplate metadataQueryRestTemplate() {
        return new RestTemplate();
    }
}
