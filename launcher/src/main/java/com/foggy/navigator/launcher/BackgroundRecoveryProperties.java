package com.foggy.navigator.launcher;

import com.foggy.navigator.spi.recovery.BackgroundRecoveryBounds;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryBoundsOverride;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryPolicy;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryPolicyOverride;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryProfile;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryProviderId;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties("navigator.background-recovery")
public class BackgroundRecoveryProperties {

    private GlobalPolicy global = new GlobalPolicy();
    private Map<String, PolicyOverride> providers = new LinkedHashMap<>();
    private Map<String, PolicyOverride> profiles = new LinkedHashMap<>();

    public GlobalPolicy getGlobal() {
        return global;
    }

    public void setGlobal(GlobalPolicy global) {
        this.global = global;
    }

    public Map<String, PolicyOverride> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, PolicyOverride> providers) {
        this.providers = providers;
    }

    public Map<String, PolicyOverride> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, PolicyOverride> profiles) {
        this.profiles = profiles;
    }

    BackgroundRecoveryPolicy globalPolicy() {
        if (global == null) {
            throw new IllegalArgumentException("global background recovery policy must be configured");
        }
        return global.toPolicy();
    }

    Map<BackgroundRecoveryProviderId, BackgroundRecoveryPolicyOverride> providerOverrides() {
        Map<BackgroundRecoveryProviderId, BackgroundRecoveryPolicyOverride> converted =
                new LinkedHashMap<>();
        safe(providers).forEach((key, value) -> putUnique(
                converted,
                BackgroundRecoveryProviderId.of(key),
                requireOverride(value, "provider", key),
                "provider"));
        return Map.copyOf(converted);
    }

    Map<BackgroundRecoveryProfile, BackgroundRecoveryPolicyOverride> profileOverrides() {
        Map<BackgroundRecoveryProfile, BackgroundRecoveryPolicyOverride> converted =
                new LinkedHashMap<>();
        safe(profiles).forEach((key, value) -> putUnique(
                converted,
                BackgroundRecoveryProfile.of(key),
                requireOverride(value, "profile", key),
                "profile"));
        return Map.copyOf(converted);
    }

    private static Map<String, PolicyOverride> safe(Map<String, PolicyOverride> values) {
        return values == null ? Map.of() : values;
    }

    private static BackgroundRecoveryPolicyOverride requireOverride(
            PolicyOverride value, String kind, String key) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "background recovery " + kind + " override is null: " + key);
        }
        return value.toOverride();
    }

    private static <K> void putUnique(
            Map<K, BackgroundRecoveryPolicyOverride> target,
            K key,
            BackgroundRecoveryPolicyOverride value,
            String kind) {
        if (target.putIfAbsent(key, value) != null) {
            throw new IllegalArgumentException(
                    "duplicate canonical background recovery " + kind + ": " + key);
        }
    }

    public static class GlobalPolicy {
        private boolean enabled = true;
        private Bounds bounds = new Bounds();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Bounds getBounds() {
            return bounds;
        }

        public void setBounds(Bounds bounds) {
            this.bounds = bounds;
        }

        BackgroundRecoveryPolicy toPolicy() {
            if (bounds == null) {
                throw new IllegalArgumentException("global background recovery bounds must be configured");
            }
            return new BackgroundRecoveryPolicy(enabled, bounds.toBounds());
        }
    }

    public static class Bounds {
        private int maxAttempts = 100;
        private Duration recoveryWindow = Duration.ofHours(24);
        private Duration initialBackoff = Duration.ofSeconds(5);
        private Duration maxBackoff = Duration.ofMinutes(5);
        private int maxConcurrentRecoveries = 4;
        private Duration scanInterval = Duration.ofMinutes(1);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getRecoveryWindow() {
            return recoveryWindow;
        }

        public void setRecoveryWindow(Duration recoveryWindow) {
            this.recoveryWindow = recoveryWindow;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public int getMaxConcurrentRecoveries() {
            return maxConcurrentRecoveries;
        }

        public void setMaxConcurrentRecoveries(int maxConcurrentRecoveries) {
            this.maxConcurrentRecoveries = maxConcurrentRecoveries;
        }

        public Duration getScanInterval() {
            return scanInterval;
        }

        public void setScanInterval(Duration scanInterval) {
            this.scanInterval = scanInterval;
        }

        BackgroundRecoveryBounds toBounds() {
            return new BackgroundRecoveryBounds(
                    maxAttempts,
                    recoveryWindow,
                    initialBackoff,
                    maxBackoff,
                    maxConcurrentRecoveries,
                    scanInterval);
        }
    }

    public static class PolicyOverride {
        private Boolean enabled;
        private BoundsOverride bounds;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public BoundsOverride getBounds() {
            return bounds;
        }

        public void setBounds(BoundsOverride bounds) {
            this.bounds = bounds;
        }

        BackgroundRecoveryPolicyOverride toOverride() {
            return new BackgroundRecoveryPolicyOverride(
                    enabled,
                    bounds == null ? null : bounds.toOverride());
        }
    }

    public static class BoundsOverride {
        private Integer maxAttempts;
        private Duration recoveryWindow;
        private Duration initialBackoff;
        private Duration maxBackoff;
        private Integer maxConcurrentRecoveries;
        private Duration scanInterval;

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getRecoveryWindow() {
            return recoveryWindow;
        }

        public void setRecoveryWindow(Duration recoveryWindow) {
            this.recoveryWindow = recoveryWindow;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public Integer getMaxConcurrentRecoveries() {
            return maxConcurrentRecoveries;
        }

        public void setMaxConcurrentRecoveries(Integer maxConcurrentRecoveries) {
            this.maxConcurrentRecoveries = maxConcurrentRecoveries;
        }

        public Duration getScanInterval() {
            return scanInterval;
        }

        public void setScanInterval(Duration scanInterval) {
            this.scanInterval = scanInterval;
        }

        BackgroundRecoveryBoundsOverride toOverride() {
            return new BackgroundRecoveryBoundsOverride(
                    maxAttempts,
                    recoveryWindow,
                    initialBackoff,
                    maxBackoff,
                    maxConcurrentRecoveries,
                    scanInterval);
        }
    }
}
