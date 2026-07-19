package com.foggy.navigator.common.authorization;

import com.foggy.navigator.common.entity.AuthorizationDecisionEntity;
import com.foggy.navigator.common.repository.AuthorizationDecisionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationDecisionAuditStoreImplTest {

    @Test
    void derivesAuditProvenanceFromCatalogAndDeploymentProviderRatherThanCallerContext() {
        AuthorizationDecisionRepository repository = mock(AuthorizationDecisionRepository.class);
        DeploymentIdentityProvider deploymentIdentityProvider = mock(DeploymentIdentityProvider.class);
        AuthorizationRouteCatalog routeCatalog = mock(AuthorizationRouteCatalog.class);
        AuthorizationDecisionAuditStore store = new AuthorizationDecisionAuditStoreImpl(
                repository, deploymentIdentityProvider, routeCatalog);
        AuthorizationContextV1 context = context(
                "caller-shaped-route-id",
                "caller-shaped-action",
                "/caller-shaped/path/with/upstreamUserToken-material");
        AuthorizationRouteManifestEntry catalogRoute = manifestRoute(
                "catalog-owned-route-id", "catalog.owned.action", "/catalog-owned/path");
        PolicyDecisionV1 canonicalDecision = PolicyDecisionV1.shadow(context,
                AuthorizationDecisionOutcome.DENY,
                AuthorizationReasonCode.AUTHZ_TRUST_PROFILE_UNKNOWN);

        when(routeCatalog.findByRouteId("caller-shaped-route-id")).thenReturn(Optional.of(catalogRoute));
        when(deploymentIdentityProvider.deploymentIdentity()).thenReturn(new DeploymentIdentity(
                "provider-owned-instance", "provider-owned-profile", DeploymentIdentitySource.CONFIGURED, true));
        when(repository.append(any(AuthorizationDecisionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuthorizationDecisionEntity persisted = store.appendShadow(
                context, canonicalDecision, LegacyEnforcementOutcome.ALLOW, 202);

        ArgumentCaptor<AuthorizationDecisionEntity> entityCaptor =
                ArgumentCaptor.forClass(AuthorizationDecisionEntity.class);
        verify(repository).append(entityCaptor.capture());
        AuthorizationDecisionEntity entity = entityCaptor.getValue();
        assertEquals(entity, persisted);
        assertEquals("catalog-owned-route-id", entity.getRouteId());
        assertEquals("catalog.owned.action", entity.getActionId());
        assertNotEquals("caller-shaped-action", entity.getActionId());
        assertEquals("provider-owned-instance", entity.getNavigatorInstanceId());
        assertEquals("provider-owned-profile", entity.getEnvironmentProfile());
        assertNotEquals(context.deployment().navigatorInstanceId(), entity.getNavigatorInstanceId());
        assertEquals("LEGACY_ALLOW_CANONICAL_DENY", entity.getDiffCode());
        assertFalse(entity.getRequestDigest().contains("upstreamUserToken-material"));
    }

    @Test
    void givesUnregisteredRoutesStableAuditValuesWithoutPersistingCallerPath() {
        AuthorizationDecisionRepository repository = mock(AuthorizationDecisionRepository.class);
        DeploymentIdentityProvider deploymentIdentityProvider = () -> new DeploymentIdentity(
                "server-instance", "local", DeploymentIdentitySource.CONFIGURED, false);
        AuthorizationRouteCatalog routeCatalog = mock(AuthorizationRouteCatalog.class);
        AuthorizationDecisionAuditStore store = new AuthorizationDecisionAuditStoreImpl(
                repository, deploymentIdentityProvider, routeCatalog);
        AuthorizationContextV1 context = context(
                "not-in-catalog",
                "caller-action",
                "/unregistered/path/with/credential-material");
        PolicyDecisionV1 canonicalDecision = PolicyDecisionV1.shadow(context,
                AuthorizationDecisionOutcome.DENY,
                AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED);

        when(routeCatalog.findByRouteId("not-in-catalog")).thenReturn(Optional.empty());
        when(repository.append(any(AuthorizationDecisionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        store.appendShadow(context, canonicalDecision, LegacyEnforcementOutcome.DENY, 404);

        ArgumentCaptor<AuthorizationDecisionEntity> entityCaptor =
                ArgumentCaptor.forClass(AuthorizationDecisionEntity.class);
        verify(repository).append(entityCaptor.capture());
        AuthorizationDecisionEntity entity = entityCaptor.getValue();
        assertEquals("UNREGISTERED_ROUTE", entity.getRouteId());
        assertEquals("unregistered.action", entity.getActionId());
        assertNotEquals("/unregistered/path/with/credential-material", entity.getRequestDigest());
        assertFalse(entity.getRequestDigest().contains("credential-material"));
    }

    private static AuthorizationContextV1 context(String routeId, String actionId, String path) {
        return new AuthorizationContextV1(
                AuthorizationSchemaV1.SCHEMA_VERSION,
                AuthorizationSchemaV1.POLICY_VERSION,
                AuthorizationSchemaV1.ACTION_CATALOG_VERSION,
                "test-build",
                UUID.randomUUID().toString(),
                new AuthorizationContextV1.Deployment(
                        "caller-owned-instance", "caller-owned-profile", "REQUEST", false),
                new AuthorizationContextV1.Principal(AuthorizationPrincipalType.NAVIGATOR_USER,
                        "principal-observation", "test", AuthorizationResolutionState.VERIFIED),
                new AuthorizationContextV1.Credential(AuthorizationCredentialLane.NAVIGATOR_JWT,
                        "credential-observation", AuthorizationResolutionState.VERIFIED),
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

    private static AuthorizationRouteManifestEntry manifestRoute(String routeId, String actionId, String path) {
        return new AuthorizationRouteManifestEntry(
                routeId,
                AuthorizationRouteCatalog.DEPLOYMENT_LAUNCHER,
                "GET",
                path,
                "MVC",
                "CatalogController.catalogOwned",
                "catalog",
                "LEGACY",
                "NONE",
                actionId,
                "NAVIGATOR_JWT",
                "catalog.target",
                "R1",
                "SHADOW",
                "IN_SCOPE",
                "REVIEWED",
                "test route",
                Set.of());
    }
}
