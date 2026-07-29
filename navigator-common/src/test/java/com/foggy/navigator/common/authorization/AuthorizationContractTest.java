package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationContractTest {

    private static final String ROUTE_ID = "mvc:get:/api/v1/health/external-surface";
    private static final String ACTION_ID = "readiness.external-surface.status";

    private final AuthorizationRouteCatalog catalog = new AuthorizationRouteCatalog();
    private final AuthorizationShadowEvaluator evaluator = new AuthorizationShadowEvaluator(catalog);

    @Test
    void catalog_isFrozenAtTheApprovedDeploymentAwareManifest() {
        assertEquals(463, catalog.size());
        assertEquals("aa33e1361f2240eaad80ce51387fb4861bc67604f31450e979435856d50d5b95",
                catalog.checksum());

        AuthorizationRouteManifestEntry actuatorRoot = catalog.findByRouteId("framework:get:/actuator")
                .orElseThrow();
        assertEquals("actuator.discovery-links.read", actuatorRoot.canonicalAction());
        assertEquals("FRAMEWORK_BOUNDARY", actuatorRoot.disposition());

        Map<String, List<AuthorizationRouteManifestEntry>> byPath = catalog.entriesByRouteId().values().stream()
                .collect(Collectors.groupingBy(AuthorizationRouteManifestEntry::path));
        assertTrue(byPath.values().stream().anyMatch(entries -> entries.stream()
                .map(AuthorizationRouteManifestEntry::deployment)
                .distinct()
                .count() > 1));
        assertTrue(byPath.values().stream().flatMap(List::stream)
                .anyMatch(entry -> AuthorizationRouteCatalog.DEPLOYMENT_OBSERVER_BFF.equals(entry.deployment())
                        && entry.routeId().startsWith("mvc:observer-bff:")));
    }

    @Test
    void decisionSerializesTheFrozenCanonicalFieldNames() throws Exception {
        PolicyDecisionV1 decision = evaluator.evaluate(completeContext());

        JsonNode serialized = new ObjectMapper().findAndRegisterModules().readTree(
                new ObjectMapper().findAndRegisterModules().writeValueAsBytes(decision));

        assertEquals("ALLOW", serialized.path("decision").asText());
        assertEquals("ENFORCEMENT", serialized.path("evaluationMode").asText());
        assertTrue(serialized.path("nonBinding").asBoolean());
        assertFalse(serialized.has("outcome"));
        assertFalse(serialized.has("shadow"));
        assertEquals(AuthorizationSchemaV1.SCHEMA_VERSION, serialized.path("schemaVersion").asText());
        assertEquals(AuthorizationSchemaV1.POLICY_VERSION, serialized.path("policyVersion").asText());
        assertEquals(AuthorizationSchemaV1.ACTION_CATALOG_VERSION,
                serialized.path("actionCatalogVersion").asText());
        assertNotNull(decision.decisionId());
        assertNotNull(decision.correlationId());
    }

    @Test
    void unsupportedSchemaPolicyAndCatalogAreStableValidationFailures() {
        assertEquals(AuthorizationReasonCode.AUTHZ_SCHEMA_UNSUPPORTED,
                AuthorizationContextValidator.validateBase(contextWithVersions("unsupported",
                        AuthorizationSchemaV1.POLICY_VERSION,
                        AuthorizationSchemaV1.ACTION_CATALOG_VERSION)).reasonCode());
        assertEquals(AuthorizationReasonCode.AUTHZ_POLICY_VERSION_UNSUPPORTED,
                AuthorizationContextValidator.validateBase(contextWithVersions(AuthorizationSchemaV1.SCHEMA_VERSION,
                        "unsupported",
                        AuthorizationSchemaV1.ACTION_CATALOG_VERSION)).reasonCode());
        assertEquals(AuthorizationReasonCode.AUTHZ_CATALOG_VERSION_MISMATCH,
                AuthorizationContextValidator.validateBase(contextWithVersions(AuthorizationSchemaV1.SCHEMA_VERSION,
                        AuthorizationSchemaV1.POLICY_VERSION,
                        "unsupported")).reasonCode());
    }

    @Test
    void unregisteredIngressAndRouteActionMetadataMismatchesFailClosedInShadow() {
        AuthorizationContextV1 unknownRoute = context(
                "mvc:post:/api/v1/not-registered", "unregistered.action",
                AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER, "POST", "/api/v1/not-registered");
        PolicyDecisionV1 unknownRouteDecision = evaluator.evaluate(unknownRoute);
        assertEquals(AuthorizationDecisionOutcome.DENY, unknownRouteDecision.decision());
        assertEquals(AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED, unknownRouteDecision.reasonCode());

        PolicyDecisionV1 wrongAction = evaluator.evaluate(context(
                ROUTE_ID, "different.action", AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER,
                "GET", "/api/v1/health/external-surface"));
        assertEquals(AuthorizationDecisionOutcome.DENY, wrongAction.decision());
        assertEquals(AuthorizationReasonCode.AUTHZ_ROUTE_ACTION_MISMATCH, wrongAction.reasonCode());

        PolicyDecisionV1 wrongDeployment = evaluator.evaluate(context(
                ROUTE_ID, ACTION_ID, AuthorizationRouteCatalog.DEPLOYMENT_OBSERVER_BFF,
                "GET", "/api/v1/health/external-surface"));
        assertEquals(AuthorizationDecisionOutcome.DENY, wrongDeployment.decision());
        assertEquals(AuthorizationReasonCode.AUTHZ_ROUTE_ACTION_MISMATCH, wrongDeployment.reasonCode());
    }

    private static AuthorizationContextV1 completeContext() {
        return context(ROUTE_ID, ACTION_ID, AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER,
                "GET", "/api/v1/health/external-surface");
    }

    private static AuthorizationContextV1 contextWithVersions(String schemaVersion,
                                                               String policyVersion,
                                                               String catalogVersion) {
        AuthorizationContextV1 context = completeContext();
        return new AuthorizationContextV1(
                schemaVersion,
                policyVersion,
                catalogVersion,
                context.serverBuild(),
                context.correlationId(),
                context.deployment(),
                context.principal(),
                context.credential(),
                context.action(),
                context.route(),
                context.trust(),
                context.target(),
                context.capability(),
                context.workerRoute(),
                context.credentialSourceConflict(),
                context.deploymentIdentityOverrideAttempt());
    }

    private static AuthorizationContextV1 context(String routeId,
                                                  String actionId,
                                                  String deployment,
                                                  String httpMethod,
                                                  String path) {
        return new AuthorizationContextV1(
                AuthorizationSchemaV1.SCHEMA_VERSION,
                AuthorizationSchemaV1.POLICY_VERSION,
                AuthorizationSchemaV1.ACTION_CATALOG_VERSION,
                "test-build",
                "test-correlation",
                new AuthorizationContextV1.Deployment("test-instance", "test", "TEST", false),
                new AuthorizationContextV1.Principal(AuthorizationPrincipalType.NAVIGATOR_USER,
                        "test-user-context", "authenticated", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Credential(AuthorizationCredentialLane.NAVIGATOR_JWT,
                        "test-user-context", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Action(actionId),
                new AuthorizationContextV1.Route(routeId, deployment, httpMethod, path),
                new AuthorizationContextV1.Trust("internal-dev", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Target("test-resolver", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Capability("test-capability", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.WorkerRoute("PHYSICAL_WORKER", AuthorizationResolutionState.VERIFIED),
                false,
                false);
    }
}
