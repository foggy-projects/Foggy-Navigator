package com.foggy.navigator.common.authorization;

import org.springframework.stereotype.Component;

/**
 * Canonical evaluator used only for P1A observation. It never invokes a
 * legacy credential service, writes an HTTP response, or becomes an
 * authorization gate.
 */
@Component
public class AuthorizationShadowEvaluator {

    private final AuthorizationRouteCatalog routeCatalog;

    public AuthorizationShadowEvaluator(AuthorizationRouteCatalog routeCatalog) {
        this.routeCatalog = routeCatalog;
    }

    public PolicyDecisionV1 evaluate(AuthorizationContextV1 context) {
        try {
            if (context != null && context.deploymentIdentityOverrideAttempt()) {
                return deny(context, AuthorizationReasonCode.AUTHZ_DEPLOYMENT_IDENTITY_OVERRIDE);
            }
            if (context != null && context.credentialSourceConflict()) {
                return deny(context, AuthorizationReasonCode.AUTHZ_CREDENTIAL_SOURCE_CONFLICT);
            }
            AuthorizationContextValidationResult validation = AuthorizationContextValidator.validateBase(context);
            if (!validation.valid()) {
                return deny(context, validation.reasonCode());
            }
            AuthorizationRouteManifestEntry entry = routeCatalog.findByRouteId(context.route().routeId())
                    .orElse(null);
            if (entry == null) {
                return deny(context, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
            }
            if (!entry.canonicalAction().equals(context.action().actionId())) {
                return deny(context, AuthorizationReasonCode.AUTHZ_ROUTE_ACTION_MISMATCH);
            }
            if (!entry.deployment().equals(context.route().deployment())
                    || !entry.httpMethod().equalsIgnoreCase(context.route().httpMethod())
                    || !entry.path().equals(context.route().path())) {
                return deny(context, AuthorizationReasonCode.AUTHZ_ROUTE_ACTION_MISMATCH);
            }
            AuthorizationRequiredSectionValidationResult requiredSections =
                    AuthorizationContextValidator.validateRequiredSections(context, entry.requiredSections());
            if (!requiredSections.valid()) {
                return PolicyDecisionV1.shadow(context, requiredSections.outcome(), requiredSections.reasonCode());
            }
            return PolicyDecisionV1.shadow(context, AuthorizationDecisionOutcome.ALLOW,
                    AuthorizationReasonCode.AUTHZ_POLICY_SHADOW_ALLOW);
        } catch (RuntimeException exception) {
            return PolicyDecisionV1.shadow(context, AuthorizationDecisionOutcome.UNKNOWN,
                    AuthorizationReasonCode.AUTHZ_SHADOW_EVALUATION_ERROR);
        }
    }

    private static PolicyDecisionV1 deny(AuthorizationContextV1 context, AuthorizationReasonCode reasonCode) {
        return PolicyDecisionV1.shadow(context, AuthorizationDecisionOutcome.DENY, reasonCode);
    }

    private static PolicyDecisionV1 unknown(AuthorizationContextV1 context, AuthorizationReasonCode reasonCode) {
        return PolicyDecisionV1.shadow(context, AuthorizationDecisionOutcome.UNKNOWN, reasonCode);
    }
}
