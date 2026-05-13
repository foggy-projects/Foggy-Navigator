package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppDTO;
import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppProvisioningCredentialEntity;
import com.foggy.navigator.business.agent.model.form.CreateClientAppForm;
import com.foggy.navigator.business.agent.model.form.IssueControlCredentialForm;
import com.foggy.navigator.business.agent.model.form.IssueProvisioningCredentialForm;
import com.foggy.navigator.business.agent.model.form.IssueRuntimeCredentialForm;
import com.foggy.navigator.business.agent.repository.ClientAppControlCredentialRepository;
import com.foggy.navigator.business.agent.repository.ClientAppProvisioningCredentialRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientAppServiceTest {

    private ClientAppRepository clientAppRepository;
    private ClientAppProvisioningCredentialRepository provisioningRepository;
    private ClientAppRuntimeCredentialRepository runtimeCredentialRepository;
    private ClientAppControlCredentialRepository controlCredentialRepository;
    private ClientAppService service;

    @BeforeEach
    void setUp() {
        clientAppRepository = mock(ClientAppRepository.class);
        provisioningRepository = mock(ClientAppProvisioningCredentialRepository.class);
        runtimeCredentialRepository = mock(ClientAppRuntimeCredentialRepository.class);
        controlCredentialRepository = mock(ClientAppControlCredentialRepository.class);
        service = new ClientAppService(clientAppRepository, provisioningRepository, runtimeCredentialRepository,
                controlCredentialRepository);

        when(clientAppRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(provisioningRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runtimeCredentialRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(controlCredentialRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void issueProvisioningCredential_binds_current_tenant() {
        IssueProvisioningCredentialForm form = new IssueProvisioningCredentialForm();
        form.setTargetTenantId("tenant-1");
        form.setMaxUses(2);

        IssuedCredentialDTO dto = service.issueProvisioningCredential("tenant-1", "admin-1", form);

        assertEquals("tenant-1", dto.getTenantId());
        assertNotNull(dto.getToken());
        verify(provisioningRepository).save(argThat(entity ->
                "tenant-1".equals(entity.getTenantId())
                        && entity.getMaxUses() == 2
                        && ClientAppService.STATUS_ACTIVE.equals(entity.getStatus())));
    }

    @Test
    void issueProvisioningCredential_rejects_cross_tenant_target() {
        IssueProvisioningCredentialForm form = new IssueProvisioningCredentialForm();
        form.setTargetTenantId("tenant-2");

        assertThrows(IllegalArgumentException.class,
                () -> service.issueProvisioningCredential("tenant-1", "admin-1", form));
    }

    @Test
    void createClientApp_consumes_tenant_bound_provisioning_credential() {
        ClientAppProvisioningCredentialEntity credential = provisioningCredential("tenant-1");
        when(provisioningRepository.findByTokenHash(anyString())).thenReturn(Optional.of(credential));

        ClientAppDTO dto = service.createClientApp("tenant-1", "admin-1", createForm("provision-token"));

        assertEquals("tenant-1", dto.getTenantId());
        assertEquals("Orders", dto.getName());
        assertEquals(1, credential.getUsedCount());
        assertEquals(ClientAppService.STATUS_USED, credential.getStatus());
    }

    @Test
    void createClientApp_rejects_cross_tenant_provisioning_credential() {
        when(provisioningRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(provisioningCredential("tenant-2")));

        assertThrows(IllegalArgumentException.class,
                () -> service.createClientApp("tenant-1", "admin-1", createForm("provision-token")));
    }

    @Test
    void createClientApp_rejects_expired_provisioning_credential() {
        ClientAppProvisioningCredentialEntity credential = provisioningCredential("tenant-1");
        credential.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(provisioningRepository.findByTokenHash(anyString())).thenReturn(Optional.of(credential));

        assertThrows(IllegalArgumentException.class,
                () -> service.createClientApp("tenant-1", "admin-1", createForm("provision-token")));
    }

    @Test
    void createClientApp_does_not_accept_runtime_secret_as_provisioning_token() {
        when(provisioningRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.createClientApp("tenant-1", "admin-1", createForm("runtime-secret")));
    }

    @Test
    void issueRuntimeCredential_requires_active_owned_app() {
        ClientAppEntity app = activeApp("tenant-1", "capp-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("capp-1", "tenant-1")).thenReturn(Optional.of(app));

        IssuedCredentialDTO dto = service.issueRuntimeCredential("tenant-1", "capp-1", new IssueRuntimeCredentialForm());

        assertEquals("capp-1", dto.getClientAppId());
        assertNotNull(dto.getAppKey());
        assertNotNull(dto.getSecret());
    }

    @Test
    void issueRuntimeCredential_rejects_disabled_app() {
        ClientAppEntity app = activeApp("tenant-1", "capp-1");
        app.setStatus(ClientAppService.STATUS_DISABLED);
        when(clientAppRepository.findByClientAppIdAndTenantId("capp-1", "tenant-1")).thenReturn(Optional.of(app));

        assertThrows(IllegalStateException.class,
                () -> service.issueRuntimeCredential("tenant-1", "capp-1", new IssueRuntimeCredentialForm()));
    }

    @Test
    void issueControlCredential_requires_active_owned_app_and_returns_controlApiKey() {
        ClientAppEntity app = activeApp("tenant-1", "capp-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("capp-1", "tenant-1")).thenReturn(Optional.of(app));
        IssueControlCredentialForm form = new IssueControlCredentialForm();
        form.setScopes(java.util.List.of(ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC));

        IssuedCredentialDTO dto = service.issueControlCredential("tenant-1", "admin-1", "capp-1", form);

        assertEquals("capp-1", dto.getClientAppId());
        assertNotNull(dto.getControlApiKey());
        assertTrue(dto.getControlApiKey().startsWith("cac_"));
        assertTrue(dto.getScopes().contains(ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC));
        verify(controlCredentialRepository).save(argThat(entity ->
                "capp-1".equals(entity.getClientAppId())
                        && "tenant-1".equals(entity.getTenantId())
                        && entity.getControlKeyHash() != null
                        && entity.getScopes().contains(ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC)));
    }

    @Test
    void updateStatus_persists_status() {
        ClientAppEntity app = activeApp("tenant-1", "capp-1");
        when(clientAppRepository.findByClientAppIdAndTenantId("capp-1", "tenant-1")).thenReturn(Optional.of(app));

        service.updateStatus("tenant-1", "capp-1", ClientAppService.STATUS_SUSPENDED);

        ArgumentCaptor<ClientAppEntity> captor = ArgumentCaptor.forClass(ClientAppEntity.class);
        verify(clientAppRepository).save(captor.capture());
        assertEquals(ClientAppService.STATUS_SUSPENDED, captor.getValue().getStatus());
    }

    private CreateClientAppForm createForm(String token) {
        CreateClientAppForm form = new CreateClientAppForm();
        form.setProvisioningToken(token);
        form.setName("Orders");
        return form;
    }

    private ClientAppProvisioningCredentialEntity provisioningCredential(String tenantId) {
        ClientAppProvisioningCredentialEntity entity = new ClientAppProvisioningCredentialEntity();
        entity.setCredentialId("capc-1");
        entity.setTenantId(tenantId);
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        entity.setMaxUses(1);
        entity.setUsedCount(0);
        return entity;
    }

    private ClientAppEntity activeApp(String tenantId, String clientAppId) {
        ClientAppEntity app = new ClientAppEntity();
        app.setTenantId(tenantId);
        app.setClientAppId(clientAppId);
        app.setName("Orders");
        app.setStatus(ClientAppService.STATUS_ACTIVE);
        return app;
    }
}
