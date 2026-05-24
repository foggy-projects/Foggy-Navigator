package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BizWorkerIdentityDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.RegisterWorkerIdentityForm;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/upstream-admin/worker-identities")
@RequiredArgsConstructor
public class UpstreamAdminWorkerIdentityController {

    private final UpstreamClientAppAdminCredentialService adminCredentialService;
    private final BizWorkerPoolService workerPoolService;

    @PostMapping
    public RX<BizWorkerIdentityDTO> registerWorkerIdentity(HttpServletRequest request,
                                                           @RequestBody RegisterWorkerIdentityForm form) {
        UpstreamClientAppAdminPrincipal principal = adminCredentialService.requireAccess(
                request,
                UpstreamBootstrapRequestService.SCOPE_WORKER_POOL_MANAGE);
        return RX.ok(workerPoolService.registerWorkerIdentity(
                ResourceOwnerType.UPSTREAM_SYSTEM,
                principal.getUpstreamSystemId(),
                form));
    }
}
