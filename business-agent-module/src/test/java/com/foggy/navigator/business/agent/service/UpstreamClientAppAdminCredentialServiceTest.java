package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.UpstreamClientAppAdminCredentialEntity;
import com.foggy.navigator.business.agent.repository.UpstreamClientAppAdminCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UpstreamClientAppAdminCredentialServiceTest {

    private UpstreamClientAppAdminCredentialRepository repository;
    private UpstreamClientAppAdminCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(UpstreamClientAppAdminCredentialRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new UpstreamClientAppAdminCredentialService(repository, new ObjectMapper());
    }

    @Test
    void requireAccessAcceptsAdminKeyHeaderAndPreservesTenantIds() {
        when(repository.findByCredentialKeyHash(anyString()))
                .thenReturn(Optional.of(activeCredential("[\"tenant-1\",\"tenant-2\"]",
                        "[\"CLIENT_APP_ADMIN\"]")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UpstreamClientAppAdminCredentialService.HEADER_ADMIN_KEY, "admin-key");

        UpstreamClientAppAdminPrincipal principal = service.requireAccess(
                request,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE);

        assertEquals("ucaac-1", principal.getCredentialId());
        assertEquals("x6-tms", principal.getPrincipalId());
        assertEquals("x6-tms", principal.getUpstreamSystemId());
        assertEquals("x6", principal.getAuthorizedClientAppNamespace());
        assertTrue(principal.getAuthorizedTenantIds().contains("tenant-1"));
        assertTrue(principal.getAuthorizedTenantIds().contains("tenant-2"));
        verify(repository).save(argThat(entity -> entity.getLastUsedAt() != null));
    }

    @Test
    void requireAccessRejectsOldAdminApiKeyHeader() {
        when(repository.findByCredentialKeyHash(anyString()))
                .thenReturn(Optional.of(activeCredential("[\"tenant-1\"]",
                        "[\"CLIENT_APP_ADMIN_ALL\"]")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Navi-Admin-Api-Key", "admin-key");

        assertThrows(SecurityException.class, () -> service.requireAccess(
                request,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE));
        verify(repository, never()).findByCredentialKeyHash(anyString());
    }

    @Test
    void requireAccessRejectsMissingScope() {
        when(repository.findByCredentialKeyHash(anyString()))
                .thenReturn(Optional.of(activeCredential("[\"tenant-1\"]",
                        "[\"CLIENT_APP_CONTROL_KEY_ISSUE\"]")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UpstreamClientAppAdminCredentialService.HEADER_ADMIN_KEY, "admin-key");

        assertThrows(SecurityException.class, () -> service.requireAccess(
                request,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE));
    }

    @Test
    void requireAccessRejectsRevokedCredential() {
        UpstreamClientAppAdminCredentialEntity credential = activeCredential("[\"tenant-1\"]",
                "[\"CLIENT_APP_ADMIN_ALL\"]");
        credential.setRevokedAt(LocalDateTime.now());
        when(repository.findByCredentialKeyHash(anyString())).thenReturn(Optional.of(credential));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UpstreamClientAppAdminCredentialService.HEADER_ADMIN_KEY, "admin-key");

        assertThrows(SecurityException.class, () -> service.requireAccess(
                request,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE));
        verify(repository, never()).save(any());
    }

    private UpstreamClientAppAdminCredentialEntity activeCredential(String tenantIdsJson, String scopesJson) {
        UpstreamClientAppAdminCredentialEntity entity = new UpstreamClientAppAdminCredentialEntity();
        entity.setCredentialId("ucaac-1");
        entity.setCredentialKeyHash(SecretTokenSupport.sha256("admin-key"));
        entity.setUpstreamSystemId("x6-tms");
        entity.setAuthorizedTenantIdsJson(tenantIdsJson);
        entity.setAuthorizedClientAppNamespace("x6");
        entity.setScopesJson(scopesJson);
        entity.setStatus(UpstreamBootstrapRequestService.CREDENTIAL_STATUS_ACTIVE);
        return entity;
    }
}
