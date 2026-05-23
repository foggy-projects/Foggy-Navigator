package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.UpstreamAdminCredentialClaimDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamAdminCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapApprovalActor;
import com.foggy.navigator.business.agent.model.form.RotateUpstreamAdminCredentialForm;
import com.foggy.navigator.business.agent.service.NaviOperatorCredentialService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/upstream-admin-credentials")
@RequiredArgsConstructor
public class AdminUpstreamAdminCredentialController {

    private final UpstreamBootstrapRequestService requestService;
    private final NaviOperatorCredentialService operatorCredentialService;

    @PostMapping("/{credentialId}/revoke")
    public RX<UpstreamAdminCredentialDTO> revoke(HttpServletRequest request,
                                                 @PathVariable String credentialId) {
        UpstreamBootstrapApprovalActor actor = operatorCredentialService.requireAdminOrOperator(request);
        return RX.ok(requestService.revokeAdminCredential(credentialId, actor));
    }

    @PostMapping("/{credentialId}/rotate")
    public RX<UpstreamAdminCredentialClaimDTO> rotate(HttpServletRequest request,
                                                      @PathVariable String credentialId,
                                                      @RequestBody(required = false) RotateUpstreamAdminCredentialForm form) {
        UpstreamBootstrapApprovalActor actor = operatorCredentialService.requireAdminOrOperator(request);
        return RX.ok(requestService.rotateAdminCredential(credentialId, form, actor));
    }
}
