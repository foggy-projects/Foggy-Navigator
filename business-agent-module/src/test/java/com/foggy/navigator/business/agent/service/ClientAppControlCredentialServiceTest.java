package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.entity.ClientAppControlCredentialEntity;
import com.foggy.navigator.business.agent.repository.ClientAppControlCredentialRepository;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ClientAppControlCredentialServiceTest {

    private ClientAppControlCredentialRepository repository;
    private ClientAppControlCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(ClientAppControlCredentialRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ClientAppControlCredentialService(repository);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void requireAccess_acceptsTenantAdminWithoutControlKey() {
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId("admin-1")
                .tenantId("tenant-1")
                .roles("TENANT_ADMIN")
                .build());

        ClientAppControlPlanePrincipal principal = service.requireAccess(
                new MockHttpServletRequest(),
                ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC,
                "capp-1");

        assertTrue(principal.isAdmin());
        assertEquals("tenant-1", principal.getTenantId());
        assertEquals("admin-1", principal.getActorUserId());
        verify(repository, never()).findByControlKeyHash(anyString());
    }

    @Test
    void requireAccess_acceptsScopedControlKeyForBoundClientApp() {
        String key = "cac_test";
        when(repository.findByControlKeyHash(anyString()))
                .thenReturn(Optional.of(activeCredential("tenant-1", "capp-1",
                        ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientAppControlCredentialService.HEADER_CONTROL_KEY, key);

        ClientAppControlPlanePrincipal principal = service.requireAccess(
                request,
                ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC,
                "capp-1");

        assertFalse(principal.isAdmin());
        assertEquals("tenant-1", principal.getTenantId());
        assertEquals("capp-1", principal.getClientAppId());
        assertEquals("client-app-control:cacc-1", principal.getActorUserId());
        verify(repository).save(argThat(entity -> entity.getLastUsedAt() != null));
    }

    @Test
    void requireAccess_rejectsCrossClientAppControlKey() {
        when(repository.findByControlKeyHash(anyString()))
                .thenReturn(Optional.of(activeCredential("tenant-1", "capp-1",
                        ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientAppControlCredentialService.HEADER_CONTROL_KEY, "cac_test");

        assertThrows(SecurityException.class, () -> service.requireAccess(
                request,
                ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC,
                "capp-2"));
    }

    @Test
    void requireAccess_rejectsMissingScope() {
        when(repository.findByControlKeyHash(anyString()))
                .thenReturn(Optional.of(activeCredential("tenant-1", "capp-1",
                        ClientAppControlCredentialService.SCOPE_SKILL_BUNDLE_SYNC)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientAppControlCredentialService.HEADER_CONTROL_KEY, "cac_test");

        assertThrows(SecurityException.class, () -> service.requireAccess(
                request,
                ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC,
                "capp-1"));
    }

    @Test
    void requireAccess_acceptsLegacyAgentBundleScopeForFunctionDelivery() {
        when(repository.findByControlKeyHash(anyString()))
                .thenReturn(Optional.of(activeCredential("tenant-1", "capp-1",
                        ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientAppControlCredentialService.HEADER_CONTROL_KEY, "cac_test");

        ClientAppControlPlanePrincipal principal = service.requireAccess(
                request,
                ClientAppControlCredentialService.SCOPE_FUNCTION_GRANT_MANAGE,
                "capp-1");

        assertFalse(principal.isAdmin());
        assertEquals("capp-1", principal.getClientAppId());
    }

    @Test
    void requireAccess_acceptsModelGrantScopeForOwnedModelConfigManagement() {
        when(repository.findByControlKeyHash(anyString()))
                .thenReturn(Optional.of(activeCredential("tenant-1", "capp-1",
                        ClientAppControlCredentialService.SCOPE_MODEL_CONFIG_GRANT_MANAGE)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientAppControlCredentialService.HEADER_CONTROL_KEY, "cac_test");

        ClientAppControlPlanePrincipal principal = service.requireAccess(
                request,
                ClientAppControlCredentialService.SCOPE_MODEL_CONFIG_MANAGE,
                "capp-1");

        assertFalse(principal.isAdmin());
        assertEquals("capp-1", principal.getClientAppId());
    }

    private ClientAppControlCredentialEntity activeCredential(String tenantId, String clientAppId, String scopes) {
        ClientAppControlCredentialEntity entity = new ClientAppControlCredentialEntity();
        entity.setCredentialId("cacc-1");
        entity.setTenantId(tenantId);
        entity.setClientAppId(clientAppId);
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        entity.setScopes(scopes);
        return entity;
    }
}
