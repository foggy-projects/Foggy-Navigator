package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ClientAppDTO;
import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamAdminCredentialClaimDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapApprovalActor;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapRequestCreatedDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapRequestDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.ClientAppControlCredentialEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeCredentialEntity;
import com.foggy.navigator.business.agent.model.entity.UpstreamBootstrapRequestEntity;
import com.foggy.navigator.business.agent.model.entity.UpstreamClientAppAdminCredentialEntity;
import com.foggy.navigator.business.agent.model.form.ApproveUpstreamBootstrapRequestForm;
import com.foggy.navigator.business.agent.model.form.ClaimUpstreamAdminCredentialForm;
import com.foggy.navigator.business.agent.model.form.CreateUpstreamBootstrapRequestForm;
import com.foggy.navigator.business.agent.model.form.EnsureUpstreamClientAppForm;
import com.foggy.navigator.business.agent.model.form.IssueControlCredentialForm;
import com.foggy.navigator.business.agent.model.form.IssueRuntimeCredentialForm;
import com.foggy.navigator.business.agent.repository.ClientAppControlCredentialRepository;
import com.foggy.navigator.business.agent.repository.ClientAppProvisioningCredentialRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeCredentialRepository;
import com.foggy.navigator.business.agent.repository.UpstreamBootstrapAuditRepository;
import com.foggy.navigator.business.agent.repository.UpstreamBootstrapRequestRepository;
import com.foggy.navigator.business.agent.repository.UpstreamClientAppAdminCredentialRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpstreamBootstrapEndToEndServiceTest {

    @Test
    void requestApproveClaimEnsureClientAppAndIssueControlKey() {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, UpstreamBootstrapRequestEntity> requestsByCodeHash = new HashMap<>();
        Map<String, UpstreamClientAppAdminCredentialEntity> adminCredentialsByHash = new HashMap<>();
        Map<String, ClientAppEntity> clientAppsById = new HashMap<>();
        Map<String, ClientAppEntity> clientAppsByUpstreamKey = new HashMap<>();

        UpstreamBootstrapRequestRepository requestRepository = mock(UpstreamBootstrapRequestRepository.class);
        when(requestRepository.save(any())).thenAnswer(inv -> {
            UpstreamBootstrapRequestEntity request = inv.getArgument(0);
            requestsByCodeHash.put(request.getRequestCodeHash(), request);
            return request;
        });
        when(requestRepository.findByRequestCodeHashForUpdate(any()))
                .thenAnswer(inv -> Optional.ofNullable(requestsByCodeHash.get(inv.getArgument(0))));

        UpstreamClientAppAdminCredentialRepository adminCredentialRepository =
                mock(UpstreamClientAppAdminCredentialRepository.class);
        when(adminCredentialRepository.save(any())).thenAnswer(inv -> {
            UpstreamClientAppAdminCredentialEntity credential = inv.getArgument(0);
            adminCredentialsByHash.put(credential.getCredentialKeyHash(), credential);
            return credential;
        });
        when(adminCredentialRepository.findByCredentialKeyHash(any()))
                .thenAnswer(inv -> Optional.ofNullable(adminCredentialsByHash.get(inv.getArgument(0))));

        UpstreamBootstrapAuditRepository auditRepository = mock(UpstreamBootstrapAuditRepository.class);
        when(auditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpstreamBootstrapRequestService requestService = new UpstreamBootstrapRequestService(
                requestRepository,
                adminCredentialRepository,
                auditRepository,
                objectMapper);

        UpstreamClientAppAdminCredentialService adminCredentialService =
                new UpstreamClientAppAdminCredentialService(adminCredentialRepository, objectMapper);

        ClientAppRepository clientAppRepository = mock(ClientAppRepository.class);
        when(clientAppRepository.save(any())).thenAnswer(inv -> {
            ClientAppEntity app = inv.getArgument(0);
            clientAppsById.put(app.getClientAppId(), app);
            clientAppsByUpstreamKey.put(upstreamKey(app.getTenantId(), app.getUpstreamSystemId(),
                    app.getUpstreamClientAppNamespace(), app.getUpstreamRef()), app);
            return app;
        });
        when(clientAppRepository.findByTenantIdAndUpstreamSystemIdAndUpstreamClientAppNamespaceAndUpstreamRef(
                any(), any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(clientAppsByUpstreamKey.get(upstreamKey(
                        inv.getArgument(0),
                        inv.getArgument(1),
                        inv.getArgument(2),
                        inv.getArgument(3)))));
        when(clientAppRepository.findByClientAppId(any()))
                .thenAnswer(inv -> Optional.ofNullable(clientAppsById.get(inv.getArgument(0))));
        when(clientAppRepository.findByClientAppIdAndTenantId(any(), any()))
                .thenAnswer(inv -> {
                    ClientAppEntity app = clientAppsById.get(inv.getArgument(0));
                    return app != null && inv.getArgument(1).equals(app.getTenantId())
                            ? Optional.of(app)
                            : Optional.empty();
                });

        ClientAppControlCredentialRepository controlCredentialRepository =
                mock(ClientAppControlCredentialRepository.class);
        when(controlCredentialRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ClientAppRuntimeCredentialRepository runtimeCredentialRepository =
                mock(ClientAppRuntimeCredentialRepository.class);
        when(runtimeCredentialRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ClientAppService clientAppService = new ClientAppService(
                clientAppRepository,
                mock(ClientAppProvisioningCredentialRepository.class),
                runtimeCredentialRepository,
                controlCredentialRepository);
        UpstreamClientAppManagementService managementService = new UpstreamClientAppManagementService(
                clientAppRepository,
                clientAppService,
                adminCredentialService);

        CreateUpstreamBootstrapRequestForm requestForm = new CreateUpstreamBootstrapRequestForm();
        requestForm.setUpstreamSystemId("x6-tms");
        requestForm.setRequestedTenantId("tenant-1");
        requestForm.setMultiTenant(true);
        requestForm.setApplicantLabel("TMS X6");
        requestForm.setReason("bootstrap tenant client apps");

        UpstreamBootstrapRequestCreatedDTO created = requestService.createRequest(requestForm, "127.0.0.1");

        ApproveUpstreamBootstrapRequestForm approveForm = new ApproveUpstreamBootstrapRequestForm();
        approveForm.setAuthorizedTenantIds(List.of("tenant-1", "tenant-2"));
        approveForm.setAuthorizedClientAppNamespace("x6");
        approveForm.setScopes(List.of("CLIENT_APP_ADMIN", "CONTROL_KEY_ISSUE", "RUNTIME_KEY_ISSUE"));
        UpstreamBootstrapRequestDTO approved = requestService.approve(
                created.getRequestCode(),
                approveForm,
                UpstreamBootstrapApprovalActor.builder()
                        .operator(true)
                        .operatorCredentialId("operator-1")
                        .build());

        ClaimUpstreamAdminCredentialForm claimForm = new ClaimUpstreamAdminCredentialForm();
        claimForm.setClaimToken(created.getClaimToken());
        UpstreamAdminCredentialClaimDTO claimed = requestService.claim(created.getRequestCode(), claimForm);

        MockHttpServletRequest adminRequest = new MockHttpServletRequest();
        adminRequest.addHeader(UpstreamClientAppAdminCredentialService.HEADER_ADMIN_KEY,
                claimed.getNaviAdminApiKey());
        UpstreamClientAppAdminPrincipal principal = adminCredentialService.requireAccess(
                adminRequest,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE);

        EnsureUpstreamClientAppForm ensureForm = new EnsureUpstreamClientAppForm();
        ensureForm.setTargetTenantId("tenant-1");
        ensureForm.setUpstreamRef("tms-tenant-a");
        ensureForm.setName("TMS Tenant A");
        ClientAppDTO clientApp = managementService.ensureClientApp(principal, ensureForm);

        IssueControlCredentialForm controlForm = new IssueControlCredentialForm();
        controlForm.setScopes(List.of(ClientAppControlCredentialService.SCOPE_AGENT_BUNDLE_SYNC));
        controlForm.setDescription("tenant bootstrap");
        IssuedCredentialDTO issuedControlKey = managementService.issueControlCredential(
                principal,
                clientApp.getClientAppId(),
                controlForm);
        UpstreamClientAppAdminPrincipal runtimePrincipal = adminCredentialService.requireAccess(
                adminRequest,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_RUNTIME_KEY_ISSUE);
        IssueRuntimeCredentialForm runtimeForm = new IssueRuntimeCredentialForm();
        runtimeForm.setDescription("tenant runtime bootstrap");
        IssuedCredentialDTO issuedRuntimeKey = managementService.issueRuntimeCredential(
                runtimePrincipal,
                clientApp.getClientAppId(),
                runtimeForm);

        assertEquals(UpstreamBootstrapRequestService.STATUS_APPROVED, approved.getStatus());
        assertEquals(List.of(
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_RUNTIME_KEY_ISSUE), approved.getScopes());
        assertNotNull(approved.getAdminCredentialExpiresAt());
        assertEquals(UpstreamBootstrapRequestService.STATUS_CONSUMED,
                requestsByCodeHash.get(SecretTokenSupport.sha256(created.getRequestCode())).getStatus());
        assertTrue(claimed.getNaviAdminApiKey().startsWith("naa_"));
        assertEquals("tenant-1", clientApp.getTenantId());
        assertEquals("x6-tms", clientApp.getUpstreamSystemId());
        assertEquals("x6", clientApp.getUpstreamClientAppNamespace());
        assertEquals("tms-tenant-a", clientApp.getUpstreamRef());
        assertEquals(clientApp.getClientAppId(), issuedControlKey.getClientAppId());
        assertTrue(issuedControlKey.getControlApiKey().startsWith("cac_"));
        assertEquals(clientApp.getClientAppId(), issuedRuntimeKey.getClientAppId());
        assertTrue(issuedRuntimeKey.getAppKey().startsWith("cak_"));
        assertTrue(issuedRuntimeKey.getSecret().startsWith("cas_"));

        verify(controlCredentialRepository).save(any(ClientAppControlCredentialEntity.class));
        verify(runtimeCredentialRepository).save(any(ClientAppRuntimeCredentialEntity.class));
        assertThrows(SecurityException.class, () -> {
            EnsureUpstreamClientAppForm deniedForm = new EnsureUpstreamClientAppForm();
            deniedForm.setTargetTenantId("tenant-3");
            deniedForm.setUpstreamRef("tms-tenant-c");
            managementService.ensureClientApp(principal, deniedForm);
        });
    }

    private static String upstreamKey(String tenantId,
                                      String upstreamSystemId,
                                      String namespace,
                                      String upstreamRef) {
        return tenantId + "|" + upstreamSystemId + "|" + namespace + "|" + upstreamRef;
    }
}
