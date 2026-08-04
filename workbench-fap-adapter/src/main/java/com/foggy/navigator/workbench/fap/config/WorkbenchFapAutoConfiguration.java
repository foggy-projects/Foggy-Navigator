package com.foggy.navigator.workbench.fap.config;

import com.foggy.agent.client.FoggyAgentPlatformClient;
import com.foggy.agent.client.FoggyAgentPlatformClientConfig;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingEntity;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingRepository;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EnableConfigurationProperties(WorkbenchFapProperties.class)
@ComponentScan("com.foggy.navigator.workbench.fap")
@EntityScan(basePackageClasses = WorkbenchFapConversationBindingEntity.class)
@EnableJpaRepositories(basePackageClasses = WorkbenchFapConversationBindingRepository.class)
public class WorkbenchFapAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "navigator.workbench.fap",
            name = "enabled",
            havingValue = "true")
    FoggyAgentPlatformClient foggyAgentPlatformClient(WorkbenchFapProperties properties) {
        return new FoggyAgentPlatformClient(new FoggyAgentPlatformClientConfig(
                properties.getAccessBaseUri(),
                properties.getRuntimeBaseUri(),
                properties.getCallerApplicationRef(),
                properties.getAccessBearerToken(),
                properties.getRuntimeBearerToken(),
                Duration.ofSeconds(properties.getTimeoutSeconds())));
    }
}
