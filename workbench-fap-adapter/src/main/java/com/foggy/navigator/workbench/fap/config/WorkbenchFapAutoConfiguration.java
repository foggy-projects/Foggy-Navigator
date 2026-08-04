package com.foggy.navigator.workbench.fap.config;

import com.foggy.agent.client.FoggyAgentPlatformClient;
import com.foggy.agent.client.FoggyAgentPlatformClientConfig;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingEntity;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingRepository;
import com.foggy.navigator.workbench.fap.service.WorkbenchFapService;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.annotation.Order;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@AutoConfiguration
@EnableConfigurationProperties(WorkbenchFapProperties.class)
@ComponentScan("com.foggy.navigator.workbench.fap")
@EntityScan(basePackageClasses = WorkbenchFapConversationBindingEntity.class)
@EnableJpaRepositories(basePackageClasses = WorkbenchFapConversationBindingRepository.class)
public class WorkbenchFapAutoConfiguration {

    /**
     * Lets the request reach Navigator's canonical MVC AuthInterceptor.
     *
     * <p>Navigator's global Spring Security chain does not parse JWTs; it only permits known
     * product namespaces far enough for the MVC interceptor to establish {@code UserContext}.
     * This profile-local chain performs the same transport handoff for FAP routes. It grants no
     * product authority: every operation still requires an authenticated UserContext and the
     * exact personal-canary owner allowlist in {@link WorkbenchFapService}. Do not broaden this
     * matcher, add alternate credentials, or move authorization into this filter chain.
     */
    @Bean
    @Order(0)
    @ConditionalOnProperty(
            prefix = "navigator.workbench.fap",
            name = "enabled",
            havingValue = "true")
    SecurityFilterChain workbenchFapTransportSecurityFilterChain(HttpSecurity http)
            throws Exception {
        return http.securityMatcher("/api/v1/workbench/fap/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }

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
