package com.foggy.navigator.spi.recovery;

/** Read-only policy port available to provider addons in later work items. */
@FunctionalInterface
public interface BackgroundRecoveryPolicyResolver {

    ResolvedBackgroundRecoveryPolicy resolve(
            BackgroundRecoveryCapabilityDeclaration declaration);
}
