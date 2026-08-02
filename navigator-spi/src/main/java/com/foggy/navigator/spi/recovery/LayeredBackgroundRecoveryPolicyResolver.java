package com.foggy.navigator.spi.recovery;

import java.util.Map;
import java.util.Objects;

/**
 * Deterministic policy resolver with the frozen precedence:
 * deployment profile &gt; provider &gt; global default.
 */
public final class LayeredBackgroundRecoveryPolicyResolver
        implements BackgroundRecoveryPolicyResolver {

    private final BackgroundRecoveryPolicy globalDefault;
    private final Map<BackgroundRecoveryProviderId, BackgroundRecoveryPolicyOverride> providerOverrides;
    private final Map<BackgroundRecoveryProfile, BackgroundRecoveryPolicyOverride> profileOverrides;
    private final BackgroundRecoveryProfile activeProfile;

    public LayeredBackgroundRecoveryPolicyResolver(
            BackgroundRecoveryPolicy globalDefault,
            Map<BackgroundRecoveryProviderId, BackgroundRecoveryPolicyOverride> providerOverrides,
            Map<BackgroundRecoveryProfile, BackgroundRecoveryPolicyOverride> profileOverrides,
            BackgroundRecoveryProfile activeProfile) {
        this.globalDefault = Objects.requireNonNull(
                globalDefault, "global background recovery policy must not be null");
        this.providerOverrides = Map.copyOf(Objects.requireNonNull(
                providerOverrides, "provider overrides must not be null"));
        this.profileOverrides = Map.copyOf(Objects.requireNonNull(
                profileOverrides, "profile overrides must not be null"));
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
    }

    @Override
    public ResolvedBackgroundRecoveryPolicy resolve(
            BackgroundRecoveryCapabilityDeclaration declaration) {
        Objects.requireNonNull(declaration, "declaration must not be null");
        BackgroundRecoveryPolicy effective = globalDefault;

        BackgroundRecoveryPolicyOverride provider = providerOverrides.get(declaration.providerId());
        if (provider != null) {
            effective = provider.applyTo(effective);
        }

        BackgroundRecoveryPolicyOverride profile = profileOverrides.get(activeProfile);
        if (profile != null) {
            effective = profile.applyTo(effective);
        }

        return new ResolvedBackgroundRecoveryPolicy(declaration, effective);
    }
}
