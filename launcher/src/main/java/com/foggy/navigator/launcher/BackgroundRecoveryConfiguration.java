package com.foggy.navigator.launcher;

import com.foggy.navigator.common.authorization.DeploymentIdentityProvider;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryPolicyResolver;
import com.foggy.navigator.spi.recovery.BackgroundRecoveryProfile;
import com.foggy.navigator.spi.recovery.LayeredBackgroundRecoveryPolicyResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BackgroundRecoveryProperties.class)
public class BackgroundRecoveryConfiguration {

    @Bean
    BackgroundRecoveryPolicyResolver backgroundRecoveryPolicyResolver(
            BackgroundRecoveryProperties properties,
            DeploymentIdentityProvider deploymentIdentityProvider) {
        BackgroundRecoveryProfile activeProfile = BackgroundRecoveryProfile.of(
                deploymentIdentityProvider.deploymentIdentity().environmentProfile());
        return new LayeredBackgroundRecoveryPolicyResolver(
                properties.globalPolicy(),
                properties.providerOverrides(),
                properties.profileOverrides(),
                activeProfile);
    }
}
