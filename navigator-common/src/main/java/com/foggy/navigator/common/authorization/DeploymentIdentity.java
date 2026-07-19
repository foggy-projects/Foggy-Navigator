package com.foggy.navigator.common.authorization;

import java.util.Objects;

/**
 * Immutable, server-owned identity of the Navigator deployment currently running.
 *
 * <p>This value is resolved once from the server deployment configuration. It must
 * never be populated from a request, an upstream selector, or a client-side profile.</p>
 */
public record DeploymentIdentity(
        String navigatorInstanceId,
        String environmentProfile,
        DeploymentIdentitySource source,
        boolean productionUsable
) {

    public DeploymentIdentity {
        navigatorInstanceId = requireText(navigatorInstanceId, "navigatorInstanceId");
        environmentProfile = requireText(environmentProfile, "environmentProfile");
        source = Objects.requireNonNull(source, "source must not be null");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
