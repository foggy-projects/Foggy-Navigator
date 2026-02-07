package com.foggy.navigator.tutor;

import com.foggy.navigator.tutor.config.TutorAgentProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * Tutor Agent 自动配置
 */
@AutoConfiguration
@EnableConfigurationProperties(TutorAgentProperties.class)
@ComponentScan(basePackages = "com.foggy.navigator.tutor")
public class TutorAgentAutoConfiguration {
}
