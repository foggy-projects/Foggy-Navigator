package com.foggy.navigator.common.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentIdentityResolverTest {

    @Test
    void resolvesExplicitLocalDeploymentIdentity() {
        MockEnvironment environment = environment("local")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "navi-local-a")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "local");

        DeploymentIdentity identity = DeploymentIdentityResolver.resolve(environment);

        assertEquals("navi-local-a", identity.navigatorInstanceId());
        assertEquals("local", identity.environmentProfile());
        assertEquals(DeploymentIdentitySource.CONFIGURED, identity.source());
        assertFalse(identity.productionUsable());
    }

    @Test
    void resolvesExplicitServerEnvironmentVariablesWithoutYamlBinding() {
        MockEnvironment environment = environment("local")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_ENVIRONMENT_VARIABLE, "navi-local-env")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_ENVIRONMENT_VARIABLE, "local");

        DeploymentIdentity identity = DeploymentIdentityResolver.resolve(environment);

        assertEquals("navi-local-env", identity.navigatorInstanceId());
        assertEquals("local", identity.environmentProfile());
        assertEquals(DeploymentIdentitySource.CONFIGURED, identity.source());
    }

    @Test
    void usesStableExplicitDevFallbackWhenNoIdentityIsConfigured() {
        MockEnvironment environment = environment("docker");

        DeploymentIdentity first = DeploymentIdentityResolver.resolve(environment);
        DeploymentIdentity second = DeploymentIdentityResolver.resolve(environment);

        assertEquals(DeploymentIdentityResolver.DEV_FALLBACK_NAVIGATOR_INSTANCE_ID,
                first.navigatorInstanceId());
        assertEquals(DeploymentIdentityResolver.DEV_FALLBACK_ENVIRONMENT_PROFILE,
                first.environmentProfile());
        assertEquals(DeploymentIdentitySource.DEV_FALLBACK, first.source());
        assertFalse(first.productionUsable());
        assertEquals(first, second);
    }

    @Test
    void requestAndClientProfileShapedPropertiesCannotOverrideConfiguredIdentity() {
        MockEnvironment environment = environment("local")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "navi-local-a")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "local")
                .withProperty("X-Navi-Navigator-Instance-Id", "request-attempt")
                .withProperty("navigator.cli.profile", "production")
                .withProperty("tenantId", "tenant-attempt")
                .withProperty("upstreamSystemId", "upstream-attempt");

        DeploymentIdentity identity = DeploymentIdentityResolver.resolve(environment);

        assertEquals("navi-local-a", identity.navigatorInstanceId());
        assertEquals("local", identity.environmentProfile());
        assertTrue(DeploymentIdentityResolver.isServerOwnedIdentityOverrideAttempt("X-Navi-Instance-Id"));
        assertTrue(DeploymentIdentityResolver.isServerOwnedIdentityOverrideAttempt("X-Navi-Environment-Profile"));
        assertTrue(DeploymentIdentityResolver.isServerOwnedIdentityOverrideAttempt("X-Navigator-Instance-Id"));
        assertTrue(DeploymentIdentityResolver.isServerOwnedIdentityOverrideAttempt("X-Navigator-Environment-Profile"));
        assertTrue(DeploymentIdentityResolver.isServerOwnedIdentityOverrideAttempt("navigatorInstanceId"));
        assertTrue(DeploymentIdentityResolver.isServerOwnedIdentityOverrideAttempt("navigator_instance_id"));
        assertTrue(DeploymentIdentityResolver.isServerOwnedIdentityOverrideAttempt("navigator-instance-id"));
        assertTrue(DeploymentIdentityResolver.isServerOwnedIdentityOverrideAttempt("environment_profile"));
        assertTrue(DeploymentIdentityResolver.isServerOwnedIdentityOverrideAttempt("environment-profile"));
        assertFalse(DeploymentIdentityResolver.isServerOwnedIdentityOverrideAttempt("X-Navi-Admin-Key"));
    }

    @Test
    void productionRequiresExplicitNonPlaceholderIdentity() {
        MockEnvironment environment = environment("prod")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "${NAVIGATOR_INSTANCE_ID}")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "production");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> DeploymentIdentityResolver.resolve(environment));

        assertTrue(exception.getMessage().contains(
                DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY));
    }

    @Test
    void productionRejectsMissingOrUnknownEnvironmentProfile() {
        MockEnvironment missingEnvironment = environment("production")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "navi-prod-a");
        MockEnvironment unknownEnvironment = environment("production")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "navi-prod-a")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "planet-x");

        assertTrue(assertThrows(IllegalStateException.class,
                () -> DeploymentIdentityResolver.resolve(missingEnvironment)).getMessage()
                .contains(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> DeploymentIdentityResolver.resolve(unknownEnvironment)).getMessage()
                .contains(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY));
    }

    @Test
    void productionRejectsUnknownInstanceIdentityAndConflictingServerSources() {
        MockEnvironment unknownInstance = environment("production")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "unknown")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "production");
        MockEnvironment devFallbackIdentity = environment("production")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY,
                        DeploymentIdentityResolver.DEV_FALLBACK_NAVIGATOR_INSTANCE_ID)
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "production");
        MockEnvironment conflictingSources = environment("production")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "navi-prod-a")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_ENVIRONMENT_VARIABLE, "navi-prod-b")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "production");

        assertTrue(assertThrows(IllegalStateException.class,
                () -> DeploymentIdentityResolver.resolve(unknownInstance)).getMessage()
                .contains(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> DeploymentIdentityResolver.resolve(devFallbackIdentity)).getMessage()
                .contains("non-dev-fallback"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> DeploymentIdentityResolver.resolve(conflictingSources)).getMessage()
                .contains("conflicts with " + DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_ENVIRONMENT_VARIABLE));
    }

    @Test
    void productionRejectsConfiguredAndActiveProfileConflicts() {
        MockEnvironment configuredConflict = environment("prod")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "navi-prod-a")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "local");
        MockEnvironment activeConflict = environment("prod", "dev")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "navi-prod-a")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "production");

        assertTrue(assertThrows(IllegalStateException.class,
                () -> DeploymentIdentityResolver.resolve(configuredConflict)).getMessage().contains("conflicts"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> DeploymentIdentityResolver.resolve(activeConflict)).getMessage().contains("conflicting active"));
    }

    @Test
    void productionAliasResolvesToCanonicalProductionIdentity() {
        MockEnvironment environment = environment("production")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "navi-prod-a")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "prod");

        DeploymentIdentity identity = DeploymentIdentityResolver.resolve(environment);

        assertEquals("production", identity.environmentProfile());
        assertTrue(identity.productionUsable());
    }

    @Test
    void equivalentProductionProfileAliasesAcrossServerSourcesDoNotConflict() {
        MockEnvironment environment = environment("production")
                .withProperty(DeploymentIdentityResolver.NAVIGATOR_INSTANCE_ID_PROPERTY, "navi-prod-a")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_PROPERTY, "prod")
                .withProperty(DeploymentIdentityResolver.ENVIRONMENT_PROFILE_ENVIRONMENT_VARIABLE, "production");

        DeploymentIdentity identity = DeploymentIdentityResolver.resolve(environment);

        assertEquals("production", identity.environmentProfile());
    }

    private static MockEnvironment environment(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return environment;
    }
}
