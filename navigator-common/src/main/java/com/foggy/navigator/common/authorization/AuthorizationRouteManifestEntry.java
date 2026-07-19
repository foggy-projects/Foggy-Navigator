package com.foggy.navigator.common.authorization;

import java.util.Objects;
import java.util.Set;

/** One deployment-aware row from the approved P0.5 method-level manifest. */
public record AuthorizationRouteManifestEntry(
        String routeId,
        String deployment,
        String httpMethod,
        String path,
        String surface,
        String controllerMethod,
        String source,
        String currentGuard,
        String currentTargetPredicate,
        String canonicalAction,
        String acceptedPrincipalLanes,
        String targetResolver,
        String riskTier,
        String migrationMode,
        String disposition,
        String reviewStatus,
        String notes,
        Set<AuthorizationRequiredSection> requiredSections
) {

    public AuthorizationRouteManifestEntry {
        requiredSections = Set.copyOf(Objects.requireNonNull(requiredSections, "requiredSections must not be null"));
    }

    public boolean requires(AuthorizationRequiredSection section) {
        return requiredSections.contains(Objects.requireNonNull(section, "section must not be null"));
    }

    public boolean requiresTarget() {
        return requires(AuthorizationRequiredSection.TARGET);
    }

    public boolean requiresCapability() {
        return requires(AuthorizationRequiredSection.CAPABILITY);
    }

    public boolean requiresWorkerRoute() {
        return requires(AuthorizationRequiredSection.WORKER_ROUTE);
    }
}
