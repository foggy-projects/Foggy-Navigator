package com.foggy.navigator.common.authorization;

import com.foggy.navigator.common.entity.AuthorizationDecisionEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationDecisionAuditDraftTest {

    private final AuthorizationRouteCatalog catalog = new AuthorizationRouteCatalog();

    @Test
    void convertsShadowEvidenceToDigestsAndUsesOnlyServerOwnedDeploymentIdentity() {
        String rawPrincipal = "upstream-user-material-must-not-persist";
        String rawCredential = "credential-material-must-not-persist";
        AuthorizationContextV1 context = context(
                "mvc:get:/api/v1/health/external-surface",
                "readiness.external-surface.status",
                "/api/v1/health/external-surface",
                rawPrincipal,
                rawCredential);
        PolicyDecisionV1 decision = PolicyDecisionV1.shadow(context,
                AuthorizationDecisionOutcome.ALLOW, AuthorizationReasonCode.AUTHZ_POLICY_SHADOW_ALLOW);
        AuthorizationRouteManifestEntry route = catalog.findByRouteId(context.route().routeId()).orElseThrow();
        AuthorizationDecisionAuditDraft draft = AuthorizationDecisionAuditDraft.fromShadow(context, decision,
                LegacyEnforcementOutcome.ALLOW, 200,
                route);

        draft.validate();
        assertEquals(AuthorizationDecisionAuditDraft.fingerprint(rawPrincipal), draft.principalFingerprint());
        assertEquals(AuthorizationDecisionAuditDraft.fingerprint(rawCredential), draft.credentialFingerprint());
        assertEquals(route.routeId(), draft.routeId());
        assertFalse(draft.toString().contains(rawPrincipal));
        assertFalse(draft.toString().contains(rawCredential));

        AuthorizationDecisionEntity entity = AuthorizationDecisionEntity.fromAuditDraft(draft,
                new DeploymentIdentity("server-owned-instance", "local", DeploymentIdentitySource.CONFIGURED, false));
        assertEquals("server-owned-instance", entity.getNavigatorInstanceId());
        assertEquals("local", entity.getEnvironmentProfile());
        assertFalse("request-attempted-instance".equals(entity.getNavigatorInstanceId()));
    }

    @Test
    void unregisteredRequestNeverPersistsItsRawPath() {
        String rawPath = "/api/v1/not-registered/upstreamUserToken-material";
        AuthorizationContextV1 context = context(
                "mvc:get:" + rawPath,
                "caller-supplied-action",
                rawPath,
                "principal-observation",
                "credential-observation");
        PolicyDecisionV1 decision = PolicyDecisionV1.shadow(context,
                AuthorizationDecisionOutcome.DENY, AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);
        AuthorizationDecisionAuditDraft draft = AuthorizationDecisionAuditDraft.fromShadow(context, decision,
                LegacyEnforcementOutcome.DENY, 404,
                null);

        assertEquals("UNREGISTERED_ROUTE", draft.routeId());
        assertEquals("unregistered.action", draft.actionId());
        assertFalse(draft.toString().contains(rawPath));
    }

    @Test
    void exposesNoPublicDraftConstructorOrFactoryAndOnlyTheStoreCanAppendShadow() {
        assertEquals(0, AuthorizationDecisionAuditDraft.class.getConstructors().length);
        assertFalse(Arrays.stream(AuthorizationDecisionAuditDraft.class.getMethods())
                .anyMatch(method -> method.getName().equals("fromShadow")
                        && Modifier.isPublic(method.getModifiers())));
        assertTrue(Arrays.stream(AuthorizationDecisionAuditStore.class.getMethods())
                .anyMatch(method -> method.getName().equals("appendShadow")
                        && Arrays.equals(method.getParameterTypes(), new Class<?>[]{
                                AuthorizationContextV1.class,
                                PolicyDecisionV1.class,
                                LegacyEnforcementOutcome.class,
                                int.class
                        })));
        assertFalse(Arrays.stream(AuthorizationDecisionAuditStore.class.getMethods())
                .anyMatch(method -> method.getName().equals("append")
                        && Arrays.equals(method.getParameterTypes(), new Class<?>[]{
                                AuthorizationDecisionAuditDraft.class
                        })));
    }

    private static AuthorizationContextV1 context(String routeId,
                                                  String actionId,
                                                  String path,
                                                  String principalReference,
                                                  String credentialReference) {
        return new AuthorizationContextV1(
                AuthorizationSchemaV1.SCHEMA_VERSION,
                AuthorizationSchemaV1.POLICY_VERSION,
                AuthorizationSchemaV1.ACTION_CATALOG_VERSION,
                AuthorizationSchemaV1.UNKNOWN_SERVER_BUILD,
                UUID.randomUUID().toString(),
                new AuthorizationContextV1.Deployment("request-attempted-instance", "local", "TEST", false),
                new AuthorizationContextV1.Principal(AuthorizationPrincipalType.NAVIGATOR_USER,
                        principalReference, "test", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Credential(AuthorizationCredentialLane.NAVIGATOR_JWT,
                        credentialReference, AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Action(actionId),
                new AuthorizationContextV1.Route(routeId, AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER,
                        "GET", path),
                new AuthorizationContextV1.Trust("internal-dev", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Target("test", AuthorizationResolutionState.VERIFIED),
                null,
                null,
                false,
                false);
    }
}
