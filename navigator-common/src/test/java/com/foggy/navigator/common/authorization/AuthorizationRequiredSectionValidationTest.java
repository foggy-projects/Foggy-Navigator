package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the closed sparse-section vocabulary independently of route names.
 * Catalog policy selects a section set; the validator only evaluates the set it
 * receives and therefore cannot fall back to action/path/surface heuristics.
 */
class AuthorizationRequiredSectionValidationTest {

    private final AuthorizationRouteCatalog catalog = new AuthorizationRouteCatalog();
    private final AuthorizationShadowEvaluator evaluator = new AuthorizationShadowEvaluator(catalog);

    @Test
    void typedSectionsSerializeWithFrozenNamesAndRemainSparseWhenAbsent() throws Exception {
        AuthorizationRouteManifestEntry entry = entry("mvc:post:/api/v1/open/client-apps/runtime-token");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        JsonNode sparse = mapper.readTree(mapper.writeValueAsBytes(contextFor(entry, null, null)));
        assertEquals(false, sparse.has("capability"));
        assertEquals(false, sparse.has("workerRoute"));

        JsonNode populated = mapper.readTree(mapper.writeValueAsBytes(contextWith(
                AuthorizationRequiredSection.PRINCIPAL, AuthorizationResolutionState.VERIFIED)));
        assertEquals("authority", populated.path("authority").path("authorityKind").asText());
        assertEquals("delegation", populated.path("delegation").path("delegationKind").asText());
        assertEquals("platform-grant", populated.path("platformGrant").path("grantReference").asText());
        assertEquals("tenant-authority", populated.path("tenantAuthority").path("tenantReference").asText());
    }

    @Test
    void everyRequiredSectionFailsClosedForMissingUnverifiedConflictAndUnknownStates() {
        for (AuthorizationRequiredSection section : AuthorizationRequiredSection.values()) {
            assertRequiredSectionOutcome(section, AuthorizationResolutionState.MISSING,
                    AuthorizationDecisionOutcome.DENY, AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_MISSING);
            assertRequiredSectionOutcome(section, AuthorizationResolutionState.UNVERIFIED,
                    AuthorizationDecisionOutcome.UNKNOWN, AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_UNVERIFIED);
            assertRequiredSectionOutcome(section, AuthorizationResolutionState.CONFLICT,
                    AuthorizationDecisionOutcome.DENY, AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_CONFLICT);
            assertRequiredSectionOutcome(section, AuthorizationResolutionState.UNKNOWN,
                    AuthorizationDecisionOutcome.UNKNOWN, AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_UNKNOWN);
        }
    }

    @Test
    void runtimeAskAndGatewayPropagateCatalogRequiredSectionFailuresAsShadowDecisions() {
        AuthorizationRouteManifestEntry ask = entry("mvc:post:/api/v1/open/agents/{agentId}/ask");
        assertShadowOutcome(contextFor(ask, null, verifiedWorkerRoute()),
                AuthorizationDecisionOutcome.DENY, AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_MISSING);
        assertShadowOutcome(contextFor(ask,
                        new AuthorizationContextV1.Capability("task", AuthorizationResolutionState.UNVERIFIED),
                        verifiedWorkerRoute()),
                AuthorizationDecisionOutcome.UNKNOWN, AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_UNVERIFIED);
        assertShadowOutcome(contextFor(ask,
                        new AuthorizationContextV1.Capability("task", AuthorizationResolutionState.CONFLICT),
                        verifiedWorkerRoute()),
                AuthorizationDecisionOutcome.DENY, AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_CONFLICT);
        assertShadowOutcome(contextFor(ask,
                        new AuthorizationContextV1.Capability("task", AuthorizationResolutionState.UNKNOWN),
                        verifiedWorkerRoute()),
                AuthorizationDecisionOutcome.UNKNOWN, AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_UNKNOWN);

        AuthorizationRouteManifestEntry gateway = entry("mvc:get:/internal/worker-gateway/v1/business-functions");
        assertShadowOutcome(contextFor(gateway, verifiedCapability(), null),
                AuthorizationDecisionOutcome.DENY, AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_MISSING);
        assertShadowOutcome(contextFor(gateway, verifiedCapability(),
                        new AuthorizationContextV1.WorkerRoute("worker", AuthorizationResolutionState.UNVERIFIED)),
                AuthorizationDecisionOutcome.UNKNOWN, AuthorizationReasonCode.AUTHZ_REQUIRED_SECTION_UNVERIFIED);
    }

    @Test
    void runtimeNonAskDoesNotRequireCapabilityUnlessTheCatalogSaysSo() {
        AuthorizationRouteManifestEntry tokenExchange =
                entry("mvc:post:/api/v1/open/client-apps/runtime-token");

        assertShadowOutcome(contextFor(tokenExchange, null, null),
                AuthorizationDecisionOutcome.ALLOW, AuthorizationReasonCode.AUTHZ_POLICY_SHADOW_ALLOW);
    }

    private void assertRequiredSectionOutcome(AuthorizationRequiredSection section,
                                              AuthorizationResolutionState state,
                                              AuthorizationDecisionOutcome expectedOutcome,
                                              AuthorizationReasonCode expectedReason) {
        AuthorizationRequiredSectionValidationResult result = assertDoesNotThrow(
                () -> AuthorizationContextValidator.validateRequiredSections(contextWith(section, state), Set.of(section)));

        assertEquals(expectedOutcome, result.outcome(), section + " " + state);
        assertEquals(expectedReason, result.reasonCode(), section + " " + state);
    }

    private void assertShadowOutcome(AuthorizationContextV1 context,
                                     AuthorizationDecisionOutcome expectedOutcome,
                                     AuthorizationReasonCode expectedReason) {
        PolicyDecisionV1 decision = assertDoesNotThrow(() -> evaluator.evaluate(context));

        assertEquals(expectedOutcome, decision.decision());
        assertEquals(expectedReason, decision.reasonCode());
        assertEquals(true, decision.nonBinding());
    }

    private AuthorizationRouteManifestEntry entry(String routeId) {
        return catalog.findByRouteId(routeId).orElseThrow();
    }

    private static AuthorizationContextV1 contextFor(AuthorizationRouteManifestEntry entry,
                                                      AuthorizationContextV1.Capability capability,
                                                      AuthorizationContextV1.WorkerRoute workerRoute) {
        return new AuthorizationContextV1(
                AuthorizationSchemaV1.SCHEMA_VERSION,
                AuthorizationSchemaV1.POLICY_VERSION,
                AuthorizationSchemaV1.ACTION_CATALOG_VERSION,
                "test-build",
                "test-correlation",
                new AuthorizationContextV1.Deployment("test-instance", "test", "TEST", false),
                new AuthorizationContextV1.Principal(AuthorizationPrincipalType.CLIENT_APP,
                        "principal", "test", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Credential(AuthorizationCredentialLane.CLIENT_APP_RUNTIME_ACCESS,
                        "credential", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Action(entry.canonicalAction()),
                new AuthorizationContextV1.Route(entry.routeId(), entry.deployment(), entry.httpMethod(), entry.path()),
                new AuthorizationContextV1.Trust("test-trust", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Target("test-target", AuthorizationResolutionState.VERIFIED),
                capability,
                workerRoute,
                false,
                false,
                new AuthorizationContextV1.Authority("test-authority", "authority", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Delegation("test-delegation", "grant", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.PlatformGrant("platform-grant", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.TenantAuthority("tenant-authority", AuthorizationResolutionState.VERIFIED));
    }

    private static AuthorizationContextV1 contextWith(AuthorizationRequiredSection changedSection,
                                                       AuthorizationResolutionState state) {
        return new AuthorizationContextV1(
                AuthorizationSchemaV1.SCHEMA_VERSION,
                AuthorizationSchemaV1.POLICY_VERSION,
                AuthorizationSchemaV1.ACTION_CATALOG_VERSION,
                "test-build",
                "test-correlation",
                new AuthorizationContextV1.Deployment("test-instance", "test", "TEST", false),
                principal(changedSection, state),
                credential(changedSection, state),
                new AuthorizationContextV1.Action("test.action"),
                new AuthorizationContextV1.Route("test:route", "TEST", "GET", "/test"),
                trust(changedSection, state),
                target(changedSection, state),
                capability(changedSection, state),
                workerRoute(changedSection, state),
                false,
                false,
                authority(changedSection, state),
                delegation(changedSection, state),
                platformGrant(changedSection, state),
                tenantAuthority(changedSection, state));
    }

    private static AuthorizationContextV1.Principal principal(AuthorizationRequiredSection changed,
                                                               AuthorizationResolutionState state) {
        return absent(changed, AuthorizationRequiredSection.PRINCIPAL, state) ? null
                : new AuthorizationContextV1.Principal(AuthorizationPrincipalType.NAVIGATOR_USER, "principal", "test",
                selectedState(changed, AuthorizationRequiredSection.PRINCIPAL, state));
    }

    private static AuthorizationContextV1.Credential credential(AuthorizationRequiredSection changed,
                                                                 AuthorizationResolutionState state) {
        return absent(changed, AuthorizationRequiredSection.CREDENTIAL, state) ? null
                : new AuthorizationContextV1.Credential(AuthorizationCredentialLane.NAVIGATOR_JWT, "credential",
                selectedState(changed, AuthorizationRequiredSection.CREDENTIAL, state));
    }

    private static AuthorizationContextV1.Trust trust(AuthorizationRequiredSection changed,
                                                       AuthorizationResolutionState state) {
        return absent(changed, AuthorizationRequiredSection.TRUST, state) ? null
                : new AuthorizationContextV1.Trust("trust", selectedState(changed, AuthorizationRequiredSection.TRUST, state));
    }

    private static AuthorizationContextV1.Target target(AuthorizationRequiredSection changed,
                                                         AuthorizationResolutionState state) {
        return absent(changed, AuthorizationRequiredSection.TARGET, state) ? null
                : new AuthorizationContextV1.Target("target", selectedState(changed, AuthorizationRequiredSection.TARGET, state));
    }

    private static AuthorizationContextV1.Capability capability(AuthorizationRequiredSection changed,
                                                                 AuthorizationResolutionState state) {
        return absent(changed, AuthorizationRequiredSection.CAPABILITY, state) ? null
                : new AuthorizationContextV1.Capability("capability",
                selectedState(changed, AuthorizationRequiredSection.CAPABILITY, state));
    }

    private static AuthorizationContextV1.WorkerRoute workerRoute(AuthorizationRequiredSection changed,
                                                                   AuthorizationResolutionState state) {
        return absent(changed, AuthorizationRequiredSection.WORKER_ROUTE, state) ? null
                : new AuthorizationContextV1.WorkerRoute("worker-route",
                selectedState(changed, AuthorizationRequiredSection.WORKER_ROUTE, state));
    }

    private static AuthorizationContextV1.Authority authority(AuthorizationRequiredSection changed,
                                                               AuthorizationResolutionState state) {
        return absent(changed, AuthorizationRequiredSection.AUTHORITY, state) ? null
                : new AuthorizationContextV1.Authority("authority", "authority-reference",
                selectedState(changed, AuthorizationRequiredSection.AUTHORITY, state));
    }

    private static AuthorizationContextV1.Delegation delegation(AuthorizationRequiredSection changed,
                                                                 AuthorizationResolutionState state) {
        return absent(changed, AuthorizationRequiredSection.DELEGATION, state) ? null
                : new AuthorizationContextV1.Delegation("delegation", "grant-reference",
                selectedState(changed, AuthorizationRequiredSection.DELEGATION, state));
    }

    private static AuthorizationContextV1.PlatformGrant platformGrant(AuthorizationRequiredSection changed,
                                                                       AuthorizationResolutionState state) {
        return absent(changed, AuthorizationRequiredSection.PLATFORM_GRANT, state) ? null
                : new AuthorizationContextV1.PlatformGrant("platform-grant",
                selectedState(changed, AuthorizationRequiredSection.PLATFORM_GRANT, state));
    }

    private static AuthorizationContextV1.TenantAuthority tenantAuthority(AuthorizationRequiredSection changed,
                                                                           AuthorizationResolutionState state) {
        return absent(changed, AuthorizationRequiredSection.TENANT_AUTHORITY, state) ? null
                : new AuthorizationContextV1.TenantAuthority("tenant-authority",
                selectedState(changed, AuthorizationRequiredSection.TENANT_AUTHORITY, state));
    }

    private static boolean absent(AuthorizationRequiredSection changed,
                                  AuthorizationRequiredSection candidate,
                                  AuthorizationResolutionState state) {
        return changed == candidate && state == AuthorizationResolutionState.MISSING;
    }

    private static AuthorizationResolutionState selectedState(AuthorizationRequiredSection changed,
                                                               AuthorizationRequiredSection candidate,
                                                               AuthorizationResolutionState state) {
        return changed == candidate ? state : AuthorizationResolutionState.VERIFIED;
    }

    private static AuthorizationContextV1.Capability verifiedCapability() {
        return new AuthorizationContextV1.Capability("task", AuthorizationResolutionState.VERIFIED);
    }

    private static AuthorizationContextV1.WorkerRoute verifiedWorkerRoute() {
        return new AuthorizationContextV1.WorkerRoute("worker", AuthorizationResolutionState.VERIFIED);
    }
}
