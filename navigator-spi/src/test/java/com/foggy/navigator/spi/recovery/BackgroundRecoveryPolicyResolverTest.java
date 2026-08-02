package com.foggy.navigator.spi.recovery;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundRecoveryPolicyResolverTest {

    private static final BackgroundRecoveryProviderId PROVIDER =
            BackgroundRecoveryProviderId.of("codex-worker");

    @Test
    void resolvesProfileThenProviderThenGlobalFieldByField() {
        BackgroundRecoveryPolicy global = new BackgroundRecoveryPolicy(true, bounds(20));
        BackgroundRecoveryPolicyOverride providerOverride = new BackgroundRecoveryPolicyOverride(
                null,
                new BackgroundRecoveryBoundsOverride(
                        8, null, Duration.ofSeconds(2), null, null, null));
        BackgroundRecoveryPolicyOverride profileOverride = new BackgroundRecoveryPolicyOverride(
                false,
                new BackgroundRecoveryBoundsOverride(
                        3, Duration.ofMinutes(30), null, null, null, null));
        BackgroundRecoveryPolicyResolver resolver = new LayeredBackgroundRecoveryPolicyResolver(
                global,
                Map.of(PROVIDER, providerOverride),
                Map.of(BackgroundRecoveryProfile.of("local-dev"), profileOverride),
                BackgroundRecoveryProfile.of("internal-dev"));

        ResolvedBackgroundRecoveryPolicy resolved = resolver.resolve(declaration());

        assertFalse(resolved.policy().enabled());
        assertEquals(3, resolved.policy().bounds().maxAttempts());
        assertEquals(Duration.ofMinutes(30), resolved.policy().bounds().recoveryWindow());
        assertEquals(Duration.ofSeconds(2), resolved.policy().bounds().initialBackoff());
        assertEquals(global.bounds().maxBackoff(), resolved.policy().bounds().maxBackoff());
    }

    @Test
    void usesProviderOverrideWhenNoProfileOverrideMatches() {
        BackgroundRecoveryPolicy global = new BackgroundRecoveryPolicy(true, bounds(20));
        BackgroundRecoveryPolicyOverride providerOverride = new BackgroundRecoveryPolicyOverride(
                false,
                new BackgroundRecoveryBoundsOverride(
                        7, null, null, null, null, null));
        BackgroundRecoveryPolicyResolver resolver = new LayeredBackgroundRecoveryPolicyResolver(
                global,
                Map.of(PROVIDER, providerOverride),
                Map.of(BackgroundRecoveryProfile.of("local-dev"),
                        new BackgroundRecoveryPolicyOverride(true, null)),
                BackgroundRecoveryProfile.of("production"));

        ResolvedBackgroundRecoveryPolicy resolved = resolver.resolve(declaration());

        assertFalse(resolved.policy().enabled());
        assertEquals(7, resolved.policy().bounds().maxAttempts());
        assertEquals(global.bounds().recoveryWindow(), resolved.policy().bounds().recoveryWindow());
    }

    @Test
    void fallsBackToGlobalForUnknownProviderAndProfile() {
        BackgroundRecoveryPolicy global = new BackgroundRecoveryPolicy(true, bounds(20));
        BackgroundRecoveryPolicyResolver resolver = new LayeredBackgroundRecoveryPolicyResolver(
                global, Map.of(), Map.of(), BackgroundRecoveryProfile.of("staging"));

        ResolvedBackgroundRecoveryPolicy resolved = resolver.resolve(declaration());

        assertEquals(global, resolved.policy());
    }

    @Test
    void capabilityDeclarationIsImmutableAndGatesUnsupportedSchedulingKinds() {
        Set<BackgroundRecoveryCapability> capabilities = new HashSet<>();
        capabilities.add(BackgroundRecoveryCapability.STARTUP_SCAN);
        BackgroundRecoveryCapabilityDeclaration declaration =
                new BackgroundRecoveryCapabilityDeclaration(PROVIDER, capabilities);
        capabilities.add(BackgroundRecoveryCapability.PERIODIC_RECOVERY_SCAN);
        BackgroundRecoveryPolicyResolver resolver = new LayeredBackgroundRecoveryPolicyResolver(
                new BackgroundRecoveryPolicy(true, bounds(20)),
                Map.of(), Map.of(), BackgroundRecoveryProfile.of("production"));

        ResolvedBackgroundRecoveryPolicy resolved = resolver.resolve(declaration);

        assertTrue(resolved.permits(BackgroundRecoveryCapability.STARTUP_SCAN));
        assertFalse(resolved.permits(BackgroundRecoveryCapability.PERIODIC_RECOVERY_SCAN));
        assertThrows(UnsupportedOperationException.class,
                () -> declaration.capabilities().add(BackgroundRecoveryCapability.DELAYED_RETRY));
    }

    @Test
    void disabledPolicyPermitsNoDeclaredCapability() {
        BackgroundRecoveryPolicyResolver resolver = new LayeredBackgroundRecoveryPolicyResolver(
                new BackgroundRecoveryPolicy(false, bounds(20)),
                Map.of(), Map.of(), BackgroundRecoveryProfile.of("local-dev"));

        ResolvedBackgroundRecoveryPolicy resolved = resolver.resolve(declaration());

        assertFalse(resolved.permits(BackgroundRecoveryCapability.STARTUP_SCAN));
        assertFalse(resolved.permits(BackgroundRecoveryCapability.DELAYED_RETRY));
    }

    @Test
    void validatesFinitePositiveAndOrderedBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new BackgroundRecoveryBounds(
                        0,
                        Duration.ofHours(1),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        1,
                        Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new BackgroundRecoveryBounds(
                        3,
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        1,
                        Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new BackgroundRecoveryBounds(
                        3,
                        Duration.ofHours(1),
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(1),
                        1,
                        Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new BackgroundRecoveryBounds(
                        3,
                        Duration.ofHours(1),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        -1,
                        Duration.ofSeconds(30)));
    }

    @Test
    void rejectsMaxBackoffBeyondRecoveryWindowAtDirectConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new BackgroundRecoveryBounds(
                        3,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(2),
                        1,
                        Duration.ofSeconds(30)));
    }

    @Test
    void rejectsSparseOverrideComposedBeyondRecoveryWindow() {
        BackgroundRecoveryBounds base = new BackgroundRecoveryBounds(
                3,
                Duration.ofHours(1),
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                1,
                Duration.ofSeconds(30));
        BackgroundRecoveryBoundsOverride override = new BackgroundRecoveryBoundsOverride(
                null,
                Duration.ofMinutes(1),
                null,
                null,
                null,
                null);

        assertThrows(IllegalArgumentException.class, () -> override.applyTo(base));
    }

    @Test
    void canonicalizesLocalDevelopmentProfileAliases() {
        assertEquals(BackgroundRecoveryProfile.of("local-dev"),
                BackgroundRecoveryProfile.of("internal-dev"));
        assertEquals(BackgroundRecoveryProfile.of("local-dev"),
                BackgroundRecoveryProfile.of("development"));
        assertEquals(BackgroundRecoveryProfile.of("local-dev"),
                BackgroundRecoveryProfile.of("local"));
        assertEquals(BackgroundRecoveryProfile.of("production"),
                BackgroundRecoveryProfile.of("prod"));
    }

    private static BackgroundRecoveryCapabilityDeclaration declaration() {
        return new BackgroundRecoveryCapabilityDeclaration(
                PROVIDER,
                Set.of(
                        BackgroundRecoveryCapability.STARTUP_SCAN,
                        BackgroundRecoveryCapability.DELAYED_RETRY));
    }

    private static BackgroundRecoveryBounds bounds(int maxAttempts) {
        return new BackgroundRecoveryBounds(
                maxAttempts,
                Duration.ofHours(24),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                4,
                Duration.ofMinutes(1));
    }
}
