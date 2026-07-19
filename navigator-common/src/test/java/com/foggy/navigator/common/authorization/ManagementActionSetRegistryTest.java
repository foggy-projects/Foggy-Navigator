package com.foggy.navigator.common.authorization;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementActionSetRegistryTest {

    private final ManagementActionSetRegistry registry = new ManagementActionSetRegistry();

    @Test
    void exposesExactlyTheFourTypedManagementLanes() {
        assertEquals(Set.of(
                        AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                        AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY,
                        AuthorizationCredentialLane.SAAS_PROVISIONING,
                        AuthorizationCredentialLane.SAAS_SECURITY_ADMIN),
                registry.definitionsByLane().keySet());
        assertEquals(AuthorizationPrincipalType.INSTANCE_ROOT,
                registry.findByLane(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL).orElseThrow().principalType());
        assertEquals(AuthorizationPrincipalType.INSTANCE_ROOT,
                registry.findByLane(AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY).orElseThrow().principalType());
        assertEquals(AuthorizationPrincipalType.SAAS_PLATFORM,
                registry.findByLane(AuthorizationCredentialLane.SAAS_PROVISIONING).orElseThrow().principalType());
        assertEquals(AuthorizationPrincipalType.SAAS_PLATFORM,
                registry.findByLane(AuthorizationCredentialLane.SAAS_SECURITY_ADMIN).orElseThrow().principalType());
    }

    @Test
    void rejectsEveryLegacyRuntimeTaskAndWorkerLane() {
        for (AuthorizationCredentialLane lane : Set.of(
                AuthorizationCredentialLane.NAVIGATOR_JWT,
                AuthorizationCredentialLane.LEGACY_UPSTREAM_ADMIN,
                AuthorizationCredentialLane.CLIENT_APP_CONTROL,
                AuthorizationCredentialLane.CLIENT_APP_RUNTIME_CREDENTIAL,
                AuthorizationCredentialLane.CLIENT_APP_RUNTIME_ACCESS,
                AuthorizationCredentialLane.TASK_SCOPED_TOKEN,
                AuthorizationCredentialLane.WORKER_CREDENTIAL,
                AuthorizationCredentialLane.UNKNOWN)) {
            assertTrue(registry.findByLane(lane).isEmpty(), () -> "unexpected management lane " + lane);
            assertFalse(registry.matches(AuthorizationPrincipalType.UPSTREAM_SYSTEM_ADMIN, lane, "anything"));
        }
    }

    @Test
    void keepsTheFiveP1bAuthenticationRoutesAsExactPairs() {
        assertEquals(Map.of(
                        "mvc:post:/api/v1/management/v1/auth/exchange", "auth.exchange",
                        "mvc:post:/api/v1/management/v1/auth/security-actions/authorize", "auth.security-authorize",
                        "mvc:get:/api/v1/management/v1/auth/whoami", "auth.whoami",
                        "mvc:get:/api/v1/management/v1/auth/permissions", "auth.permissions.inspect",
                        "mvc:post:/api/v1/management/v1/auth/explain", "auth.decision.explain"),
                registry.managementEndpointActions());
        assertFalse(registry.isRegisteredEndpointAction(
                "mvc:post:/api/v1/management/v1/auth/exchange", "auth.security-authorize"));
        assertFalse(registry.isRegisteredEndpointAction(
                "mvc:post:/api/v1/management/v1/auth/unknown", "auth.exchange"));
    }

    @Test
    void keepsControlAndSecurityActionsInSeparateLanes() {
        assertTrue(registry.allows(ManagementActionSetRegistry.INSTANCE_ROOT_CONTROL_V1, "auth.exchange"));
        assertFalse(registry.allows(ManagementActionSetRegistry.INSTANCE_ROOT_CONTROL_V1, "auth.security-authorize"));
        assertTrue(registry.allows(ManagementActionSetRegistry.INSTANCE_ROOT_SECURITY_V1, "auth.security-authorize"));
        assertFalse(registry.allows(ManagementActionSetRegistry.INSTANCE_ROOT_SECURITY_V1, "auth.exchange"));
        assertTrue(registry.allows(ManagementActionSetRegistry.SAAS_PROVISIONING_V1, "tenant.create"));
        assertFalse(registry.allows(ManagementActionSetRegistry.SAAS_PROVISIONING_V1, "tenant.offboard"));
        assertTrue(registry.allows(ManagementActionSetRegistry.SAAS_SECURITY_ADMIN_V1, "tenant.offboard"));
    }
}
