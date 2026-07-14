package com.foggy.navigator.business.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerGatewayPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void externalModeIsDisabledByDefault() {
        contextRunner.run(context -> assertThat(
                context.getBean(WorkerGatewayProperties.class).isExternalEnabled()).isFalse());
    }

    @Test
    void externalModeRequiresExplicitTrue() {
        contextRunner
                .withPropertyValues("navigator.worker-gateway.external-enabled=true")
                .run(context -> assertThat(
                        context.getBean(WorkerGatewayProperties.class).isExternalEnabled()).isTrue());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WorkerGatewayProperties.class)
    static class TestConfiguration {
    }
}
