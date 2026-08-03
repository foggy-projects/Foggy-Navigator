package com.foggy.navigator.session.config;

import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Duration;

/**
 * Session Module 自动配置
 */
@AutoConfiguration(after = JpaRepositoriesAutoConfiguration.class)
@ComponentScan(basePackages = {
    "com.foggy.navigator.session.service",
    "com.foggy.navigator.session.controller",
    "com.foggy.navigator.session.sse",
    "com.foggy.navigator.session.event",
    "com.foggy.navigator.session.filter",
    "com.foggy.navigator.session.registry",
    "com.foggy.navigator.session.agent",
    "com.foggy.navigator.session.util",
    "com.foggy.navigator.session.lifecycle",
    "com.foggy.navigator.session.command"
})
@EntityScan(basePackages = {
    "com.foggy.navigator.common.entity",
    "com.foggy.navigator.session.lifecycle.persistence",
    "com.foggy.navigator.session.command.persistence"
})
@EnableJpaRepositories(basePackages = {
    "com.foggy.navigator.session.repository",
    "com.foggy.navigator.session.lifecycle.repository",
    "com.foggy.navigator.session.command.repository"
})
@EnableConfigurationProperties({
        ErrorDiagnosticProperties.class,
        com.foggy.navigator.session.lifecycle.LifecycleActivationProperties.class
})
@EnableAsync
@EnableScheduling
public class SessionModuleAutoConfiguration {

    public static final String CANONICAL_COMMAND_AUTHORITY_CLOCK =
            "canonicalCommandAuthorityClock";
    public static final String CANONICAL_COMMAND_POLICY_VERSION =
            "navi.command-receipt-policy.v1";
    public static final Duration CANONICAL_COMMAND_AUTHORIZATION_VALIDITY =
            Duration.ofMinutes(5);

    @Bean(CANONICAL_COMMAND_AUTHORITY_CLOCK)
    @ConditionalOnMissingBean(name = CANONICAL_COMMAND_AUTHORITY_CLOCK)
    public Clock canonicalCommandAuthorityClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(VerifiedCommandAuthorizationDecision.ServerAuthority.class)
    public VerifiedCommandAuthorizationDecision.ServerAuthority
            canonicalCommandServerAuthority(
                    @Qualifier(CANONICAL_COMMAND_AUTHORITY_CLOCK) Clock clock) {
        return new VerifiedCommandAuthorizationDecision.ServerAuthority(
                CANONICAL_COMMAND_POLICY_VERSION,
                clock,
                CANONICAL_COMMAND_AUTHORIZATION_VALIDITY);
    }

    @Bean
    public com.foggy.navigator.session.lifecycle.TerminalCleanupPlanFactory
            terminalCleanupPlanFactory() {
        return new com.foggy.navigator.session.lifecycle.TerminalCleanupPlanFactory();
    }

    @Bean("sessionEventExecutor")
    public AsyncTaskExecutor sessionEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("session-event-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
