package com.foggy.navigator.business.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
        Duration configuredMax = effectiveMaxTtl();
        Duration configuredTtl = isPositive(ttl) ? ttl : DEFAULT_TTL;
        return configuredTtl.compareTo(configuredMax) > 0 ? configuredMax : configuredTtl;
    }

    public Duration effectiveMaxTtl() {
        Duration configuredMax = isPositive(maxTtl) ? maxTtl : DEFAULT_MAX_TTL;
        return configuredMax.compareTo(DEFAULT_MAX_TTL) > 0
                ? DEFAULT_MAX_TTL
                : configuredMax;
    }

    private boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
