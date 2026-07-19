package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/** Canonical, non-enforcing decision emitted by the P1A shadow evaluator. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyDecisionV1(
        String schemaVersion,
        String policyVersion,
        String actionCatalogVersion,
        String serverBuild,
        String decisionId,
        String correlationId,
        AuthorizationEvaluationMode evaluationMode,
        AuthorizationDecisionOutcome decision,
        AuthorizationReasonCode reasonCode,
        boolean nonBinding,
        String actionId,
        String routeId,
        Instant evaluatedAt
) {

    public static PolicyDecisionV1 shadow(AuthorizationContextV1 context,
                                          AuthorizationDecisionOutcome outcome,
                                          AuthorizationReasonCode reasonCode) {
        return new PolicyDecisionV1(
                context != null && context.schemaVersion() != null
                        ? context.schemaVersion() : AuthorizationSchemaV1.SCHEMA_VERSION,
                context != null && context.policyVersion() != null
                        ? context.policyVersion() : AuthorizationSchemaV1.POLICY_VERSION,
                context != null && context.actionCatalogVersion() != null
                        ? context.actionCatalogVersion() : AuthorizationSchemaV1.ACTION_CATALOG_VERSION,
                context != null && context.serverBuild() != null
                        ? context.serverBuild() : AuthorizationSchemaV1.UNKNOWN_SERVER_BUILD,
                UUID.randomUUID().toString(),
                context != null && context.correlationId() != null
                        ? context.correlationId() : UUID.randomUUID().toString(),
                AuthorizationEvaluationMode.ENFORCEMENT,
                outcome,
                reasonCode,
                true,
                context != null && context.action() != null ? context.action().actionId() : null,
                context != null && context.route() != null ? context.route().routeId() : null,
                Instant.now()
        );
    }
}
