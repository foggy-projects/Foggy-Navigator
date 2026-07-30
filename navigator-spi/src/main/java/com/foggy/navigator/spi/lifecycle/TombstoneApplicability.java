package com.foggy.navigator.spi.lifecycle;

public record TombstoneApplicability(
        boolean capabilityDomainSupported,
        String safeReasonCode
) {
}
