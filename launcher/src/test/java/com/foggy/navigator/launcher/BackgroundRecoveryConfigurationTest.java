package com.foggy.navigator.launcher;

import com.foggy.navigator.common.authorization.DeploymentIdentity;
import com.foggy.navigator.common.authorization.DeploymentIdentityProvider;
import com.foggy.navigator.common.authorization.DeploymentIdentitySource;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryCapability;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryCapabilityDeclaration;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryPolicyResolver;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryProviderId;
import com.foggy.navigator.spi.recovery.ResolvedBackgroundRecoveryPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundRecoveryConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BackgroundRecoveryConfiguration.class)
            .withBean(DeploymentIdentityProvider.class, () -> () -> new DeploymentIdentity(
                    "navi-local-dev",
                    "internal-dev",
                    DeploymentIdentitySource.DEV_FALLBACK,
                    false));

    @Test
    void bindsGlobalProviderAndCanonicalLocalDevProfileWithFrozenPrecedence() {
        contextRunner
                .withPropertyValues(
                        "navigator.background-recovery.global.enabled=true",
                        "navigator.background-recovery.global.bounds.max-attempts=20",
                        "navigator.background-recovery.global.bounds.recovery-window=PT24H",
                        "navigator.background-recovery.global.bounds.initial-backoff=PT5S",
                        "navigator.background-recovery.global.bounds.max-backoff=PT5M",
                        "navigator.background-recovery.global.bounds.max-concurrent-recoveries=4",
                        "navigator.background-recovery.global.bounds.scan-interval=PT1M",
                        "navigator.background-recovery.providers.codex-worker.enabled=true",
                        "navigator.background-recovery.providers.codex-worker.bounds.max-attempts=8",
                        "navigator.background-recovery.profiles.local-dev.enabled=false",
                        "navigator.background-recovery.profiles.local-dev.bounds.max-attempts=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BackgroundRecoveryPolicyResolver resolver =
                            context.getBean(BackgroundRecoveryPolicyResolver.class);

                    ResolvedBackgroundRecoveryPolicy resolved = resolver.resolve(
                            new BackgroundRecoveryCapabilityDeclaration(
                                    BackgroundRecoveryProviderId.of("codex-worker"),
                                    Set.of(BackgroundRecoveryCapability.STARTUP_SCAN)));

                    assertThat(resolved.policy().enabled()).isFalse();
                    assertThat(resolved.policy().bounds().maxAttempts()).isEqualTo(3);
                    assertThat(resolved.policy().bounds().recoveryWindow()).isEqualTo(Duration.ofHours(24));
                });
    }

    @Test
    void rejectsInvalidBoundConfigurationAtAssemblyTime() {
        contextRunner
                .withPropertyValues(
                        "navigator.background-recovery.global.bounds.max-attempts=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("maxAttempts");
                });
    }

    @Test
    void applicationDefaultsKeepCompatibilityOnAndLocalDevelopmentExplicitlyOff() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("navigator.background-recovery.global.enabled"))
                .isEqualTo("${NAVIGATOR_BACKGROUND_RECOVERY_ENABLED:true}");
        assertThat(properties.getProperty(
                "navigator.background-recovery.global.bounds.max-attempts"))
                .isEqualTo("${NAVIGATOR_BACKGROUND_RECOVERY_MAX_ATTEMPTS:100}");
        assertThat(properties.getProperty(
                "navigator.background-recovery.profiles.local-dev.enabled"))
                .isEqualTo("false");
    }
}
