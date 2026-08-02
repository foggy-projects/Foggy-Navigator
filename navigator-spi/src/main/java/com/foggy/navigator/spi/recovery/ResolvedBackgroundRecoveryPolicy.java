package com.foggy.navigator.spi.recovery;

import java.util.Objects;

/** Effective policy paired with the provider's declared automatic capabilities. */
public record ResolvedBackgroundRecoveryPolicy(
        BackgroundRecoveryCapabilityDeclaration declaration,
        BackgroundRecoveryPolicy policy) {

    public ResolvedBackgroundRecoveryPolicy {
        declaration = Objects.requireNonNull(declaration, "declaration must not be null");
        policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public boolean permits(BackgroundRecoveryCapability capability) {
        return policy.enabled()
                && declaration.capabilities().contains(Objects.requireNonNull(
                        capability, "capability must not be null"));
    }
}
