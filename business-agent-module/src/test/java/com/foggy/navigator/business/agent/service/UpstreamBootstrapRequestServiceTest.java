package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.UpstreamAdminCredentialClaimDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamAdminCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapApprovalActor;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapRequestCreatedDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapRequestDTO;
import com.foggy.navigator.business.agent.model.entity.UpstreamBootstrapRequestEntity;
import com.foggy.navigator.business.agent.model.entity.UpstreamClientAppAdminCredentialEntity;
import com.foggy.navigator.business.agent.model.form.ApproveUpstreamBootstrapRequestForm;
import com.foggy.navigator.business.agent.model.form.ClaimUpstreamAdminCredentialForm;
import com.foggy.navigator.business.agent.model.form.CreateUpstreamBootstrapRequestForm;
import com.foggy.navigator.business.agent.repository.UpstreamBootstrapAuditRepository;
import com.foggy.navigator.business.agent.repository.UpstreamBootstrapRequestRepository;
import com.foggy.navigator.business.agent.repository.UpstreamClientAppAdminCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UpstreamBootstrapRequestServiceTest {

    private UpstreamBootstrapRequestRepository requestRepository;
    private UpstreamClientAppAdminCredentialRepository adminCredentialRepository;
    private UpstreamBootstrapAuditRepository auditRepository;
    private UpstreamBootstrapRequestService service;

    @BeforeEach
    void setUp() {
        requestRepository = mock(UpstreamBootstrapRequestRepository.class);
        adminCredentialRepository = mock(UpstreamClientAppAdminCredentialRepository.class);
        auditRepository = mock(UpstreamBootstrapAuditRepository.class);
        when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(adminCredentialRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(auditRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new UpstreamBootstrapRequestService(
                requestRepository,
                adminCredentialRepository,
                auditRepository,
                new ObjectMapper());
    }

    @Test
    void createRequestReturnsOneTimeCodesButStoresHashesOnly() {
        CreateUpstreamBootstrapRequestForm form = new CreateUpstreamBootstrapRequestForm();
        form.setUpstreamSystemId("x6-tms");
        form.setRequestedTenantId("tenant-1");
        form.setMultiTenant(true);
        form.setApplicantLabel("TMS");

        UpstreamBootstrapRequestCreatedDTO dto = service.createRequest(form, "127.0.0.1");

        assertNotNull(dto.getRequestCode());
        assertNotNull(dto.getClaimToken());
        assertEquals(UpstreamBootstrapRequestService.STATUS_PENDING, dto.getStatus());

        ArgumentCaptor<UpstreamBootstrapRequestEntity> captor =
                ArgumentCaptor.forClass(UpstreamBootstrapRequestEntity.class);
        verify(requestRepository).save(captor.capture());
        UpstreamBootstrapRequestEntity saved = captor.getValue();
        assertNotEquals(dto.getRequestCode(), saved.getRequestCodeHash());
        assertNotEquals(dto.getClaimToken(), saved.getClaimTokenHash());
        assertEquals(SecretTokenSupport.sha256(dto.getRequestCode()), saved.getRequestCodeHash());
        assertEquals(SecretTokenSupport.sha256(dto.getClaimToken()), saved.getClaimTokenHash());
        assertEquals("x6-tms", saved.getUpstreamSystemId());
        assertEquals("tenant-1", saved.getRequestedTenantId());
        assertTrue(saved.getMultiTenant());
        verify(auditRepository).save(argThat(audit -> "REQUESTED".equals(audit.getEventType())));
    }

    @Test
    void approveAndClaimIssuesAdminKeyOnce() {
        UpstreamBootstrapRequestEntity request = pendingRequest("request-code", "claim-token", "tenant-1");
        when(requestRepository.findByRequestCodeHashForUpdate(SecretTokenSupport.sha256("request-code")))
                .thenReturn(Optional.of(request));

        ApproveUpstreamBootstrapRequestForm approveForm = new ApproveUpstreamBootstrapRequestForm();
        approveForm.setAuthorizedTenantIds(List.of("tenant-1", "tenant-2"));
        approveForm.setAuthorizedClientAppNamespace("x6");
        approveForm.setScopes(List.of("CLIENT_APP_ADMIN", "CONTROL_KEY_ISSUE"));
        approveForm.setClaimTtlMinutes(30L);

        UpstreamBootstrapRequestDTO approved = service.approve("request-code", approveForm, operatorActor());

        assertEquals(UpstreamBootstrapRequestService.STATUS_APPROVED, approved.getStatus());
        assertEquals(List.of("tenant-1", "tenant-2"), approved.getAuthorizedTenantIds());
        assertEquals("x6", approved.getAuthorizedClientAppNamespace());
        assertEquals(List.of(
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE), approved.getScopes());
        assertNotNull(approved.getAdminCredentialExpiresAt());
        assertTrue(approved.getAdminCredentialExpiresAt().isAfter(LocalDateTime.now().plusHours(23)));
        verify(auditRepository).save(argThat(audit -> "APPROVED".equals(audit.getEventType())));

        ClaimUpstreamAdminCredentialForm claimForm = new ClaimUpstreamAdminCredentialForm();
        claimForm.setClaimToken("claim-token");

        UpstreamAdminCredentialClaimDTO claimed = service.claim("request-code", claimForm);

        assertEquals(UpstreamBootstrapRequestService.STATUS_CONSUMED, request.getStatus());
        assertNotNull(claimed.getNaviAdminApiKey());
        assertTrue(claimed.getNaviAdminApiKey().startsWith("naa_"));
        assertEquals(List.of("tenant-1", "tenant-2"), claimed.getAuthorizedTenantIds());
        assertEquals(approved.getAdminCredentialExpiresAt(), claimed.getExpiresAt());
        verify(adminCredentialRepository).save(argThat(credential ->
                credential.getCredentialKeyHash() != null
                        && !credential.getCredentialKeyHash().equals(claimed.getNaviAdminApiKey())
                        && "x6-tms".equals(credential.getUpstreamSystemId())
                        && credential.getExpiresAt() != null
                        && credential.getCredentialKeySuffix() != null));
        verify(auditRepository).save(argThat(audit -> "CLAIMED".equals(audit.getEventType())));

        assertThrows(IllegalArgumentException.class, () -> service.claim("request-code", claimForm));
    }

    @Test
    void claimRejectsWrongTokenWithoutIssuingCredential() {
        UpstreamBootstrapRequestEntity request = approvedRequest("request-code", "claim-token", "tenant-1");
        when(requestRepository.findByRequestCodeHashForUpdate(SecretTokenSupport.sha256("request-code")))
                .thenReturn(Optional.of(request));

        ClaimUpstreamAdminCredentialForm claimForm = new ClaimUpstreamAdminCredentialForm();
        claimForm.setClaimToken("wrong-token");

        assertThrows(SecurityException.class, () -> service.claim("request-code", claimForm));
        verify(adminCredentialRepository, never()).save(any());
        assertEquals(UpstreamBootstrapRequestService.STATUS_APPROVED, request.getStatus());
    }

    @Test
    void tenantAdminCannotApproveRequestFromAnotherTenant() {
        UpstreamBootstrapRequestEntity request = pendingRequest("request-code", "claim-token", "tenant-2");
        when(requestRepository.findByRequestCodeHashForUpdate(anyString())).thenReturn(Optional.of(request));

        UpstreamBootstrapApprovalActor actor = UpstreamBootstrapApprovalActor.builder()
                .tenantAdmin(true)
                .tenantId("tenant-1")
                .userId("admin-1")
                .build();

        assertThrows(SecurityException.class, () -> service.approve("request-code", null, actor));
    }

    @Test
    void expiredPendingRequestCannotBeApproved() {
        UpstreamBootstrapRequestEntity request = pendingRequest("request-code", "claim-token", "tenant-1");
        request.setRequestExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(requestRepository.findByRequestCodeHashForUpdate(anyString())).thenReturn(Optional.of(request));

        assertThrows(IllegalArgumentException.class,
                () -> service.approve("request-code", null, operatorActor()));
        assertEquals(UpstreamBootstrapRequestService.STATUS_EXPIRED, request.getStatus());
        verify(auditRepository).save(argThat(audit -> "EXPIRED".equals(audit.getEventType())));
    }

    @Test
    void revokeAdminCredentialMarksCredentialInactiveAndAudits() {
        UpstreamBootstrapRequestEntity request = approvedRequest("request-code", "claim-token", "tenant-1");
        UpstreamClientAppAdminCredentialEntity credential = activeCredential();
        when(adminCredentialRepository.findByCredentialIdForUpdate("ucaac-1")).thenReturn(Optional.of(credential));
        when(requestRepository.findByRequestId("ubreq-1")).thenReturn(Optional.of(request));

        UpstreamAdminCredentialDTO dto = service.revokeAdminCredential("ucaac-1", operatorActor());

        assertEquals(UpstreamBootstrapRequestService.CREDENTIAL_STATUS_REVOKED, dto.getStatus());
        assertNotNull(dto.getRevokedAt());
        verify(adminCredentialRepository).save(argThat(saved ->
                "ucaac-1".equals(saved.getCredentialId())
                        && UpstreamBootstrapRequestService.CREDENTIAL_STATUS_REVOKED.equals(saved.getStatus())
                        && saved.getRevokedAt() != null));
        verify(auditRepository).save(argThat(audit -> "ADMIN_CREDENTIAL_REVOKED".equals(audit.getEventType())));
    }

    @Test
    void rotateAdminCredentialRevokesOldCredentialAndReturnsNewOneTimeKey() {
        UpstreamBootstrapRequestEntity request = approvedRequest("request-code", "claim-token", "tenant-1");
        UpstreamClientAppAdminCredentialEntity oldCredential = activeCredential();
        oldCredential.setExpiresAt(LocalDateTime.now().plusHours(2));
        when(adminCredentialRepository.findByCredentialIdForUpdate("ucaac-1")).thenReturn(Optional.of(oldCredential));
        when(requestRepository.findByRequestId("ubreq-1")).thenReturn(Optional.of(request));

        UpstreamAdminCredentialClaimDTO rotated = service.rotateAdminCredential("ucaac-1", null, operatorActor());

        assertNotNull(rotated.getNaviAdminApiKey());
        assertTrue(rotated.getNaviAdminApiKey().startsWith("naa_"));
        assertNotEquals("ucaac-1", rotated.getCredentialId());
        assertEquals(UpstreamBootstrapRequestService.CREDENTIAL_STATUS_REVOKED, oldCredential.getStatus());
        assertNotNull(oldCredential.getRevokedAt());
        verify(adminCredentialRepository).save(argThat(saved ->
                !"ucaac-1".equals(saved.getCredentialId())
                        && UpstreamBootstrapRequestService.CREDENTIAL_STATUS_ACTIVE.equals(saved.getStatus())
                        && saved.getCredentialKeyHash() != null
                        && !saved.getCredentialKeyHash().equals(rotated.getNaviAdminApiKey())
                        && "ubreq-1".equals(saved.getSourceRequestId())));
        verify(auditRepository).save(argThat(audit -> "ADMIN_CREDENTIAL_ROTATED".equals(audit.getEventType())));
    }

    private UpstreamBootstrapRequestEntity pendingRequest(String requestCode, String claimToken, String tenantId) {
        UpstreamBootstrapRequestEntity entity = new UpstreamBootstrapRequestEntity();
        entity.setRequestId("ubreq-1");
        entity.setRequestCodeHash(SecretTokenSupport.sha256(requestCode));
        entity.setRequestCodeSuffix("st-code");
        entity.setClaimTokenHash(SecretTokenSupport.sha256(claimToken));
        entity.setUpstreamSystemId("x6-tms");
        entity.setRequestedTenantId(tenantId);
        entity.setMultiTenant(true);
        entity.setStatus(UpstreamBootstrapRequestService.STATUS_PENDING);
        entity.setRequestExpiresAt(LocalDateTime.now().plusMinutes(30));
        return entity;
    }

    private UpstreamBootstrapRequestEntity approvedRequest(String requestCode, String claimToken, String tenantId) {
        UpstreamBootstrapRequestEntity entity = pendingRequest(requestCode, claimToken, tenantId);
        entity.setStatus(UpstreamBootstrapRequestService.STATUS_APPROVED);
        entity.setApprovedAt(LocalDateTime.now());
        entity.setClaimExpiresAt(LocalDateTime.now().plusMinutes(30));
        entity.setAuthorizedTenantIdsJson("[\"tenant-1\"]");
        entity.setAuthorizedClientAppNamespace("x6");
        entity.setScopesJson("[\"CLIENT_APP_MANAGE\"]");
        return entity;
    }

    private UpstreamClientAppAdminCredentialEntity activeCredential() {
        UpstreamClientAppAdminCredentialEntity entity = new UpstreamClientAppAdminCredentialEntity();
        entity.setCredentialId("ucaac-1");
        entity.setCredentialKeyHash(SecretTokenSupport.sha256("admin-key"));
        entity.setCredentialKeyPrefix("naa_secret_");
        entity.setCredentialKeySuffix("min-key");
        entity.setUpstreamSystemId("x6-tms");
        entity.setAuthorizedTenantIdsJson("[\"tenant-1\"]");
        entity.setAuthorizedClientAppNamespace("x6");
        entity.setScopesJson("[\"CLIENT_APP_MANAGE\",\"CLIENT_APP_CONTROL_KEY_ISSUE\"]");
        entity.setStatus(UpstreamBootstrapRequestService.CREDENTIAL_STATUS_ACTIVE);
        entity.setExpiresAt(LocalDateTime.now().plusHours(1));
        entity.setSourceRequestId("ubreq-1");
        return entity;
    }

    private UpstreamBootstrapApprovalActor operatorActor() {
        return UpstreamBootstrapApprovalActor.builder()
                .operator(true)
                .operatorCredentialId("operator-1")
                .build();
    }
}
