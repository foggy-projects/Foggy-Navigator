package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Sparse canonical request context. Optional sections stay absent until an
 * owning resolver can verify them; P1A must not invent missing authority.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthorizationContextV1(
        String schemaVersion,
        String policyVersion,
        String actionCatalogVersion,
        String serverBuild,
        String correlationId,
        Deployment deployment,
        Principal principal,
        Credential credential,
        Action action,
        Route route,
        Trust trust,
        Target target,
        Capability capability,
        WorkerRoute workerRoute,
        boolean credentialSourceConflict,
        boolean deploymentIdentityOverrideAttempt,
        Authority authority,
        Delegation delegation,
        PlatformGrant platformGrant,
        TenantAuthority tenantAuthority
) {

    /**
     * Compatibility constructor for existing P1A producers while the newly
     * modelled sparse sections remain absent. Catalog declarations, not this
     * convenience constructor, decide whether a section is required.
     */
    public AuthorizationContextV1(
            String schemaVersion,
            String policyVersion,
            String actionCatalogVersion,
            String serverBuild,
            String correlationId,
            Deployment deployment,
            Principal principal,
            Credential credential,
            Action action,
            Route route,
            Trust trust,
            Target target,
            Capability capability,
            WorkerRoute workerRoute,
            boolean credentialSourceConflict,
            boolean deploymentIdentityOverrideAttempt
    ) {
        this(schemaVersion, policyVersion, actionCatalogVersion, serverBuild, correlationId,
                deployment, principal, credential, action, route, trust, target, capability, workerRoute,
                credentialSourceConflict, deploymentIdentityOverrideAttempt, null, null, null, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Deployment(
            String navigatorInstanceId,
            String environmentProfile,
            String source,
            boolean productionUsable
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Principal(
            AuthorizationPrincipalType principalType,
            String principalReference,
            String assurance,
            AuthorizationResolutionState resolutionState
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Credential(
            AuthorizationCredentialLane credentialLane,
            String credentialReference,
            AuthorizationResolutionState resolutionState
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Action(String actionId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Route(
            String routeId,
            String deployment,
            String httpMethod,
            String path
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Trust(String trustProfile, AuthorizationResolutionState resolutionState) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Target(String resolver, AuthorizationResolutionState resolutionState) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Capability(String capabilityKind, AuthorizationResolutionState resolutionState) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkerRoute(String routeKind, AuthorizationResolutionState resolutionState) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Authority(
            String authorityKind,
            String authorityReference,
            AuthorizationResolutionState resolutionState
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delegation(
            String delegationKind,
            String grantReference,
            AuthorizationResolutionState resolutionState
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlatformGrant(
            String grantReference,
            AuthorizationResolutionState resolutionState
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TenantAuthority(
            String tenantReference,
            AuthorizationResolutionState resolutionState
    ) {
    }
}
