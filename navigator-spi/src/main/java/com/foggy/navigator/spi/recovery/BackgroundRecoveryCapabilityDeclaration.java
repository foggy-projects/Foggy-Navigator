package com.foggy.navigator.spi.recovery;

import java.util.Objects;
import java.util.Set;

/**
 * A provider's actual automatic recovery mechanisms.
 *
 * <p>Providers may deliberately declare an empty set; shared policy must not
 * invent symmetry or capabilities which the provider does not implement.</p>
 */
public record BackgroundRecoveryCapabilityDeclaration(
        BackgroundRecoveryProviderId providerId,
        Set<BackgroundRecoveryCapability> capabilities) {

    public BackgroundRecoveryCapabilityDeclaration {
        providerId = Objects.requireNonNull(providerId, "providerId must not be null");
        capabilities = Set.copyOf(Objects.requireNonNull(
                capabilities, "background recovery capabilities must not be null"));
    }
}
