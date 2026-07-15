package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BizWorkerCredentialDTO;
import com.foggy.navigator.business.agent.model.form.RotateWorkerCredentialForm;
import com.foggy.navigator.business.agent.service.BizWorkerCredentialService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/business-agent/worker-identities/{workerId}/credential")
@RequireAuth(roles = {"SUPER_ADMIN"})
@RequiredArgsConstructor
public class BizWorkerCredentialController {

    private final BizWorkerCredentialService workerCredentialService;

    @PostMapping("/rotate")
    public RX<BizWorkerCredentialDTO> rotate(
            HttpServletResponse response,
            @PathVariable String workerId,
            @RequestBody(required = false) RotateWorkerCredentialForm form) {
        disableCredentialResponseCaching(response);
        return RX.ok(workerCredentialService.rotatePlatformCredential(
                workerId,
                form == null ? null : form.getTtlSeconds()));
    }

    @DeleteMapping
    public RX<BizWorkerCredentialDTO> revoke(@PathVariable String workerId) {
        return RX.ok(workerCredentialService.revokePlatformCredential(workerId));
    }

    private void disableCredentialResponseCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }
}
