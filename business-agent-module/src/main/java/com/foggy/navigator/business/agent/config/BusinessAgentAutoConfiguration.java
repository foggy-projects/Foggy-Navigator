package com.foggy.navigator.business.agent.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterInvoker;
import com.foggy.navigator.business.agent.service.adapter.CompositeBusinessFunctionAdapterInvoker;
import com.foggy.navigator.business.agent.service.adapter.LocalEchoBusinessFunctionAdapterInvoker;
import com.foggy.navigator.business.agent.service.adapter.RestBusinessFunctionAdapterInvoker;
import com.foggy.navigator.business.agent.service.ClientAppUpstreamRouteService;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;

@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.business.agent.service",
        "com.foggy.navigator.business.agent.controller"
})
@EntityScan(basePackages = {
        "com.foggy.navigator.business.agent.model.entity",
        "com.foggy.navigator.common.entity"
})
@EnableJpaRepositories(basePackages = {
        "com.foggy.navigator.business.agent.repository",
        "com.foggy.navigator.common.repository"
})
public class BusinessAgentAutoConfiguration {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    @Primary
    public BusinessFunctionAdapterInvoker businessFunctionAdapterInvoker(ObjectMapper objectMapper,
                                                                         RestTemplate restTemplate,
                                                                         Environment environment,
                                                                         ClientAppUserGrantService userGrantService,
                                                                         ClientAppUpstreamRouteService upstreamRouteService) {
        return new CompositeBusinessFunctionAdapterInvoker(
                Arrays.asList(
                        new LocalEchoBusinessFunctionAdapterInvoker(objectMapper),
                        new RestBusinessFunctionAdapterInvoker(
                                objectMapper,
                                restTemplate,
                                environment,
                                userGrantService,
                                upstreamRouteService)
                ),
                objectMapper
        );
    }
}
