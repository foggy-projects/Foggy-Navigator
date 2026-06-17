package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayResumeResponseDTO;
import com.foggy.navigator.business.agent.model.form.WorkerGatewayResumeForm;
import com.foggy.navigator.business.agent.service.BusinessFunctionSuspensionService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/business-agent/suspensions")
@RequiredArgsConstructor
public class BusinessFunctionApprovalController {

    private final BusinessFunctionSuspensionService suspensionService;
    private final ClientAppControlCredentialService controlCredentialService;

    @PostMapping("/{suspendId}/resume")
    public WorkerGatewayResumeResponseDTO resumeSuspension(
            HttpServletRequest request,
            @PathVariable String suspendId,
            @RequestBody WorkerGatewayResumeForm form) {

        ClientAppControlPlanePrincipal principal = controlCredentialService.requireAccess(
                request,
                ClientAppControlCredentialService.SCOPE_FUNCTION_SUSPENSION_RESUME,
                null);
        suspensionService.resumeSuspension(
                principal.getTenantId(),
                principal.getActorUserId(),
                suspendId,
                form,
                principal.isAdmin() ? null : principal.getClientAppId());

        WorkerGatewayResumeResponseDTO response = new WorkerGatewayResumeResponseDTO();
        response.setStatus("resume_dispatched");
        response.setSuspendId(suspendId);
        response.setResumeRef("resume_" + suspendId);

        return response;
    }
}
