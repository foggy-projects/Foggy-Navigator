package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.EnsureUpstreamClientAppForm;
import com.foggy.navigator.business.agent.model.form.IssueControlCredentialForm;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.business.agent.repository.UpstreamClientAppAdminCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UpstreamClientAppManagementServiceTest {

    private ClientAppRepository clientAppRepository;
    private ClientAppService clientAppService;
    private UpstreamClientAppManagementService service;

    @BeforeEach
    void setUp() {
        clientAppRepository = mock(ClientAppRepository.class);
        clientAppService = mock(ClientAppService.class);
        when(clientAppRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UpstreamClientAppAdminCredentialService credentialService =
                new UpstreamClientAppAdminCredentialService(
                        mock(UpstreamClientAppAdminCredentialRepository.class),
                        new ObjectMapper());
        service = new UpstreamClientAppManagementService(clientAppRepository, clientAppService, credentialService);
    }

    @Test
    void ensureClientAppCreatesUpstreamScopedClientApp() {
        when(clientAppRepository.findByTenantIdAndUpstreamSystemIdAndUpstreamClientAppNamespaceAndUpstreamRef(
                "tenant-1", "x6-tms", "x6", "tms-tenant-a"))
                .thenReturn(Optional.empty());

        var dto = service.ensureClientApp(principal("tenant-1", "tenant-2"), ensureForm("tenant-1", "tms-tenant-a"));

        assertEquals("tenant-1", dto.getTenantId());
        assertEquals("Orders A", dto.getName());
        assertEquals("x6-tms", dto.getUpstreamSystemId());
        assertEquals("x6", dto.getUpstreamClientAppNamespace());
        assertEquals("tms-tenant-a", dto.getUpstreamRef());
        verify(clientAppRepository).save(argThat(app ->
                app.getClientAppId().startsWith("capp_")
                        && "upstream-admin:ucaac-1".equals(app.getCreatedBy())
                        && ClientAppService.STATUS_ACTIVE.equals(app.getStatus())));
    }

    @Test
    void ensureClientAppRejectsUnauthorizedTenant() {
        assertThrows(SecurityException.class,
                () -> service.ensureClientApp(principal("tenant-1"), ensureForm("tenant-2", "tms-tenant-b")));

        verify(clientAppRepository, never()).save(any());
    }

    @Test
    void ensureClientAppReusesExistingManagedClientApp() {
        ClientAppEntity existing = activeApp("tenant-1", "capp-1", "x6-tms", "x6", "tms-tenant-a");
        when(clientAppRepository.findByTenantIdAndUpstreamSystemIdAndUpstreamClientAppNamespaceAndUpstreamRef(
                "tenant-1", "x6-tms", "x6", "tms-tenant-a"))
                .thenReturn(Optional.of(existing));
        EnsureUpstreamClientAppForm form = ensureForm("tenant-1", "tms-tenant-a");
        form.setName("Orders A Updated");

        var dto = service.ensureClientApp(principal("tenant-1"), form);

        assertEquals("capp-1", dto.getClientAppId());
        assertEquals("Orders A Updated", dto.getName());
        verify(clientAppRepository).save(existing);
    }

    @Test
    void issueControlCredentialDelegatesForManagedClientApp() {
        ClientAppEntity app = activeApp("tenant-1", "capp-1", "x6-tms", "x6", "tms-tenant-a");
        when(clientAppRepository.findByClientAppId("capp-1")).thenReturn(Optional.of(app));
        IssuedCredentialDTO issued = new IssuedCredentialDTO();
        issued.setClientAppId("capp-1");
        issued.setControlApiKey("cac-secret");
        when(clientAppService.issueControlCredential(eq("tenant-1"), eq("upstream-admin:ucaac-1"),
                eq("capp-1"), any(IssueControlCredentialForm.class)))
                .thenReturn(issued);

        IssuedCredentialDTO dto = service.issueControlCredential(
                principal("tenant-1"),
                "capp-1",
                new IssueControlCredentialForm());

        assertEquals("cac-secret", dto.getControlApiKey());
    }

    @Test
    void issueControlCredentialRejectsOtherUpstreamSystemClientApp() {
        ClientAppEntity app = activeApp("tenant-1", "capp-1", "other-system", "x6", "tms-tenant-a");
        when(clientAppRepository.findByClientAppId("capp-1")).thenReturn(Optional.of(app));

        assertThrows(SecurityException.class, () -> service.issueControlCredential(
                principal("tenant-1"),
                "capp-1",
                new IssueControlCredentialForm()));

        verify(clientAppService, never()).issueControlCredential(any(), any(), any(), any());
    }

    private EnsureUpstreamClientAppForm ensureForm(String tenantId, String upstreamRef) {
        EnsureUpstreamClientAppForm form = new EnsureUpstreamClientAppForm();
        form.setTargetTenantId(tenantId);
        form.setUpstreamRef(upstreamRef);
        form.setName("Orders A");
        return form;
    }

    private UpstreamClientAppAdminPrincipal principal(String... tenantIds) {
        return UpstreamClientAppAdminPrincipal.builder()
                .credentialId("ucaac-1")
                .upstreamSystemId("x6-tms")
                .authorizedClientAppNamespace("x6")
                .authorizedTenantIds(Set.of(tenantIds))
                .scopes(Set.of(
                        UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE,
                        UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE))
                .build();
    }

    private ClientAppEntity activeApp(String tenantId,
                                      String clientAppId,
                                      String upstreamSystemId,
                                      String namespace,
                                      String upstreamRef) {
        ClientAppEntity app = new ClientAppEntity();
        app.setTenantId(tenantId);
        app.setClientAppId(clientAppId);
        app.setName("Orders");
        app.setStatus(ClientAppService.STATUS_ACTIVE);
        app.setUpstreamSystemId(upstreamSystemId);
        app.setUpstreamClientAppNamespace(namespace);
        app.setUpstreamRef(upstreamRef);
        return app;
    }
}
