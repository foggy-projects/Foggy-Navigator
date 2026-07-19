package com.foggy.navigator.common.authorization;

import java.util.Objects;

/**
 * Exact, digest-only binding required to use a {@link ManagementTokenPurpose#SECURITY_ACTION}
 * token. Build it from canonical request representations before issuing a token;
 * raw target, impact and reason material must not enter audit or token records.
 */
public record ManagementSecurityActionBinding(
        String actionId,
        String targetDigest,
        String impactDigest,
        String reasonDigest
) {

    public static ManagementSecurityActionBinding fromCanonicalRepresentations(
            OpaqueSecretHasher hasher,
            String actionId,
            String targetRepresentation,
            String impactRepresentation,
            String reasonRepresentation
    ) {
        Objects.requireNonNull(hasher, "hasher must not be null");
        return new ManagementSecurityActionBinding(
                actionId,
                hasher.hashUtf8(targetRepresentation),
                hasher.hashUtf8(impactRepresentation),
                hasher.hashUtf8(reasonRepresentation)
        );
    }

    public boolean complete() {
        return hasText(actionId) && hasText(targetDigest) && hasText(impactDigest) && hasText(reasonDigest);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
