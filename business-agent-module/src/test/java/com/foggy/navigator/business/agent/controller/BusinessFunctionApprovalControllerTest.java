package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayResumeResponseDTO;
import com.foggy.navigator.business.agent.model.form.WorkerGatewayResumeForm;
import com.foggy.navigator.business.agent.service.BusinessFunctionSuspensionService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessFunctionApprovalControllerTest {

    @Test
    void resume_usesClientAppControlPrincipalAndServerSideClientAppGuard() {
        BusinessFunctionSuspensionService suspensionService = mock(BusinessFunctionSuspensionService.class);
        ClientAppControlCredentialService credentialService = mock(ClientAppControlCredentialService.class);
        BusinessFunctionApprovalController controller =
                new BusinessFunctionApprovalController(suspensionService, credentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientAppControlCredentialService.HEADER_CONTROL_KEY, "cac-test");
        WorkerGatewayResumeForm form = resumeForm("approved");

        when(credentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_FUNCTION_SUSPENSION_RESUME),
                eq(null)))
                .thenReturn(ClientAppControlPlanePrincipal.builder()
                        .admin(false)
                        .tenantId("tenant-1")
                        .clientAppId("capp-1")
                        .actorUserId("client-app-control:cacc-1")
                        .principalType("CLIENT_APP")
                        .principalId("capp-1")
                        .scopes(Set.of(ClientAppControlCredentialService.SCOPE_FUNCTION_SUSPENSION_RESUME))
                        .build());

        WorkerGatewayResumeResponseDTO response = controller.resumeSuspension(request, "sus_1", form);

        assertEquals("resume_dispatched", response.getStatus());
        assertEquals("sus_1", response.getSuspendId());
        verify(suspensionService).resumeSuspension(
                "tenant-1",
                "client-app-control:cacc-1",
                "sus_1",
                form,
                "capp-1");
    }

    @Test
    void resume_keepsTenantAdminPrincipalUnscopedToClientApp() {
        BusinessFunctionSuspensionService suspensionService = mock(BusinessFunctionSuspensionService.class);
        ClientAppControlCredentialService credentialService = mock(ClientAppControlCredentialService.class);
        BusinessFunctionApprovalController controller =
                new BusinessFunctionApprovalController(suspensionService, credentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        WorkerGatewayResumeForm form = resumeForm("rejected");

        when(credentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_FUNCTION_SUSPENSION_RESUME),
                eq(null)))
                .thenReturn(ClientAppControlPlanePrincipal.builder()
                        .admin(true)
                        .tenantId("tenant-1")
                        .actorUserId("admin-1")
                        .principalType("PLATFORM")
                        .principalId("admin-1")
                        .scopes(Set.of(ClientAppControlCredentialService.SCOPE_ALL))
                        .build());

        controller.resumeSuspension(request, "sus_1", form);

        verify(suspensionService).resumeSuspension(
                "tenant-1",
                "admin-1",
                "sus_1",
                form,
                null);
    }

    private WorkerGatewayResumeForm resumeForm(String status) {
        WorkerGatewayResumeForm form = new WorkerGatewayResumeForm();
        WorkerGatewayResumeForm.ApprovalResult result = new WorkerGatewayResumeForm.ApprovalResult();
        result.setStatus(status);
        form.setApprovalResult(result);
        return form;
    }
}
