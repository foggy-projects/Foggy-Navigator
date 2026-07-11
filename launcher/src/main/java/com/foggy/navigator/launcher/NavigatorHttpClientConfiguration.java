package com.foggy.navigator.launcher;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

/** Application-level HTTP client defaults. */
@Configuration(proxyBeanMethods = false)
public class NavigatorHttpClientConfiguration {

    @Bean("navigatorDefaultRestTemplate")
    @Primary
    public RestTemplate navigatorDefaultRestTemplate() {
        return new RestTemplate();
    }
}
