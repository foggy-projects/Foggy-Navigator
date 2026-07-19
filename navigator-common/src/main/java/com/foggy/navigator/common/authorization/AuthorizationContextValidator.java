package com.foggy.navigator.common.authorization;

import java.util.Set;

/** Envelope and catalog-declared sparse-section validation for canonical contexts. */
public final class AuthorizationContextValidator {

    private AuthorizationContextValidator() {
    }

    public static AuthorizationContextValidationResult validateBase(AuthorizationContextV1 context) {
        if (context == null) {
            return AuthorizationContextValidationResult.invalid(AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_MISSING);
        }
        if (!AuthorizationSchemaV1.SCHEMA_VERSION.equals(context.schemaVersion())) {
            return AuthorizationContextValidationResult.invalid(AuthorizationReasonCode.AUTHZ_SCHEMA_UNSUPPORTED);
        }
        if (!AuthorizationSchemaV1.POLICY_VERSION.equals(context.policyVersion())) {
            return AuthorizationContextValidationResult.invalid(AuthorizationReasonCode.AUTHZ_POLICY_VERSION_UNSUPPORTED);
        }
        if (!AuthorizationSchemaV1.ACTION_CATALOG_VERSION.equals(context.actionCatalogVersion())) {
            return AuthorizationContextValidationResult.invalid(AuthorizationReasonCode.AUTHZ_CATALOG_VERSION_MISMATCH);
        }
        if (blank(context.serverBuild()) || blank(context.correlationId())
                || context.deployment() == null || blank(context.deployment().navigatorInstanceId())
                || blank(context.deployment().environmentProfile()) || blank(context.deployment().source())) {
            return AuthorizationContextValidationResult.invalid(AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_MISSING);
        }
        if (context.action() == null || blank(context.action().actionId())
                || context.route() == null || blank(context.route().routeId())
                || blank(context.route().deployment()) || blank(context.route().httpMethod()) || blank(context.route().path())) {
            return AuthorizationContextValidationResult.invalid(AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_MISSING);
        }
        return AuthorizationContextValidationResult.accepted();
    }

    /**
     * Validates only the typed sections explicitly declared by a catalog
     * entry. No action name, path, surface, risk tier, or absent-field default
     * participates in this decision.
     */
    public static AuthorizationRequiredSectionValidationResult validateRequiredSections(
            AuthorizationContextV1 context,
            Set<AuthorizationRequiredSection> requiredSections
    ) {
        if (context == null || requiredSections == null) {
            return AuthorizationRequiredSectionValidationResult.denied(
                    AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_MISSING);
        }
        for (AuthorizationRequiredSection section : AuthorizationRequiredSection.values()) {
            if (!requiredSections.contains(section)) {
                continue;
            }
            SectionStatus status = sectionStatus(context, section);
            if (status == SectionStatus.MISSING) {
                return AuthorizationRequiredSectionValidationResult.denied(
                        AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_MISSING);
            }
            if (status == SectionStatus.CONFLICT) {
                return AuthorizationRequiredSectionValidationResult.denied(
                        AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_CONFLICT);
            }
            if (status == SectionStatus.UNVERIFIED) {
                return AuthorizationRequiredSectionValidationResult.unknown(
                        AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_UNVERIFIED);
            }
            if (status == SectionStatus.UNKNOWN) {
                return AuthorizationRequiredSectionValidationResult.unknown(
                        AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_UNKNOWN);
            }
        }
        return AuthorizationRequiredSectionValidationResult.accepted();
    }

    private static SectionStatus sectionStatus(AuthorizationContextV1 context,
                                               AuthorizationRequiredSection section) {
        return switch (section) {
            case PRINCIPAL -> principalStatus(context.principal());
            case CREDENTIAL -> credentialStatus(context.credential());
            case TRUST -> trustStatus(context.trust());
            case AUTHORITY -> authorityStatus(context.authority());
            case DELEGATION -> delegationStatus(context.delegation());
            case TARGET -> targetStatus(context.target());
            case PLATFORM_GRANT -> platformGrantStatus(context.platformGrant());
            case TENANT_AUTHORITY -> tenantAuthorityStatus(context.tenantAuthority());
            case CAPABILITY -> capabilityStatus(context.capability());
            case WORKER_ROUTE -> workerRouteStatus(context.workerRoute());
        };
    }

    private static SectionStatus principalStatus(AuthorizationContextV1.Principal principal) {
        if (principal == null || principal.principalType() == null || blank(principal.principalReference())) {
            return SectionStatus.MISSING;
        }
        if (principal.principalType() == AuthorizationPrincipalType.UNKNOWN) {
            return SectionStatus.UNKNOWN;
        }
        return resolutionStatus(principal.resolutionState());
    }

    private static SectionStatus credentialStatus(AuthorizationContextV1.Credential credential) {
        if (credential == null || credential.credentialLane() == null || blank(credential.credentialReference())) {
            return SectionStatus.MISSING;
        }
        if (credential.credentialLane() == AuthorizationCredentialLane.UNKNOWN) {
            return SectionStatus.UNKNOWN;
        }
        return resolutionStatus(credential.resolutionState());
    }

    private static SectionStatus trustStatus(AuthorizationContextV1.Trust trust) {
        return trust == null || blank(trust.trustProfile())
                ? SectionStatus.MISSING : resolutionStatus(trust.resolutionState());
    }

    private static SectionStatus authorityStatus(AuthorizationContextV1.Authority authority) {
        return authority == null || blank(authority.authorityKind()) || blank(authority.authorityReference())
                ? SectionStatus.MISSING : resolutionStatus(authority.resolutionState());
    }

    private static SectionStatus delegationStatus(AuthorizationContextV1.Delegation delegation) {
        return delegation == null || blank(delegation.delegationKind()) || blank(delegation.grantReference())
                ? SectionStatus.MISSING : resolutionStatus(delegation.resolutionState());
    }

    private static SectionStatus targetStatus(AuthorizationContextV1.Target target) {
        return target == null || blank(target.resolver())
                ? SectionStatus.MISSING : resolutionStatus(target.resolutionState());
    }

    private static SectionStatus platformGrantStatus(AuthorizationContextV1.PlatformGrant platformGrant) {
        return platformGrant == null || blank(platformGrant.grantReference())
                ? SectionStatus.MISSING : resolutionStatus(platformGrant.resolutionState());
    }

    private static SectionStatus tenantAuthorityStatus(AuthorizationContextV1.TenantAuthority tenantAuthority) {
        return tenantAuthority == null || blank(tenantAuthority.tenantReference())
                ? SectionStatus.MISSING : resolutionStatus(tenantAuthority.resolutionState());
    }

    private static SectionStatus capabilityStatus(AuthorizationContextV1.Capability capability) {
        return capability == null || blank(capability.capabilityKind())
                ? SectionStatus.MISSING : resolutionStatus(capability.resolutionState());
    }

    private static SectionStatus workerRouteStatus(AuthorizationContextV1.WorkerRoute workerRoute) {
        return workerRoute == null || blank(workerRoute.routeKind())
                ? SectionStatus.MISSING : resolutionStatus(workerRoute.resolutionState());
    }

    private static SectionStatus resolutionStatus(AuthorizationResolutionState resolutionState) {
        if (resolutionState == null) {
            return SectionStatus.UNKNOWN;
        }
        return switch (resolutionState) {
            case VERIFIED -> SectionStatus.VERIFIED;
            case UNVERIFIED -> SectionStatus.UNVERIFIED;
            case MISSING -> SectionStatus.MISSING;
            case CONFLICT -> SectionStatus.CONFLICT;
            case UNKNOWN -> SectionStatus.UNKNOWN;
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private enum SectionStatus {
        VERIFIED,
        UNVERIFIED,
        MISSING,
        CONFLICT,
        UNKNOWN
    }
}
