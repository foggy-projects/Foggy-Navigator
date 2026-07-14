package com.foggy.navigator.business.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "navigator.business-agent.task-token")
public class BusinessTaskScopedTokenProperties {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final Duration DEFAULT_MAX_TTL = Duration.ofMinutes(60);

    private Duration ttl = DEFAULT_TTL;
    private Duration maxTtl = DEFAULT_MAX_TTL;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getMaxTtl() {
        return maxTtl;
    }

    public void setMaxTtl(Duration maxTtl) {
        this.maxTtl = maxTtl;
    }

    public Duration effectiveTtl() {
        Duration configuredMax = isPositive(maxTtl) ? maxTtl : DEFAULT_MAX_TTL;
        if (configuredMax.compareTo(DEFAULT_MAX_TTL) > 0) {
            configuredMax = DEFAULT_MAX_TTL;
        }
        Duration configuredTtl = isPositive(ttl) ? ttl : DEFAULT_TTL;
        return configuredTtl.compareTo(configuredMax) > 0 ? configuredMax : configuredTtl;
    }

    private boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
