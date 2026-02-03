package com.foggy.navigator.tutor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.tutor.config.TutorAgentProperties;
import com.foggy.navigator.tutor.tool.CodingAgentToolExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Tutor Agent 自动配置
 */
@AutoConfiguration
@EnableConfigurationProperties(TutorAgentProperties.class)
@ComponentScan(basePackages = "com.foggy.navigator.tutor")
public class TutorAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CodingAgentToolExecutor codingAgentToolExecutor(
            TutorAgentProperties properties,
            ObjectMapper objectMapper) {
        return new CodingAgentToolExecutor(properties, objectMapper);
    }
}
