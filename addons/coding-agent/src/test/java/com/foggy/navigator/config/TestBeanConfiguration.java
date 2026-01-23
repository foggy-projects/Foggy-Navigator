package com.foggy.navigator.config;

import com.foggy.navigator.foundation.git.OpenHandsClient;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 测试配置 - 提供测试所需的 Mock beans
 */
@TestConfiguration
@Profile("test")
public class TestBeanConfiguration {

    @Bean
    @Primary
    public OpenHandsClient openHandsClient() {
        return Mockito.mock(OpenHandsClient.class);
    }
}
