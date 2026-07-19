package com.foggy.navigator.common.config;

import com.foggy.navigator.common.authorization.DeploymentIdentityProvider;
import com.foggy.navigator.common.authorization.DeploymentIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CommonAutoConfigurationTest {

    @Test
    void componentScanIncludesMigrationPackage() {
        ComponentScan componentScan = CommonAutoConfiguration.class.getAnnotation(ComponentScan.class);

        assertTrue(Arrays.asList(componentScan.basePackages())
                .contains("com.foggy.navigator.common.migration"));
    }

    @Test
    void deploymentIdentityProviderKeepsTheStartupResolutionImmutable() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "navi-local-a")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "local");
        DeploymentIdentityProvider provider = new CommonAutoConfiguration()
                .deploymentIdentityProvider(environment);
        environment.withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "navi-local-b");

        assertEquals("navi-local-a", provider.deploymentIdentity().navigatorInstanceId());
    }
}
