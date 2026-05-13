package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.E2eModelConfigEnsureResultDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.form.EnsureE2eModelConfigForm;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggy.navigator.business.agent.service.E2eModelConfigEnsureService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/business-agent/client-apps/{clientAppId}/e2e-model-config")
@RequiredArgsConstructor
public class E2eModelConfigController {

    private final E2eModelConfigEnsureService service;
    private final ClientAppControlCredentialService controlCredentialService;

    @PostMapping("/ensure")
    public E2eModelConfigEnsureResultDTO ensure(HttpServletRequest request,
                                                @PathVariable String clientAppId,
                                                @RequestBody EnsureE2eModelConfigForm form) {
        ClientAppControlPlanePrincipal principal = controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_E2E_MODEL_ENSURE, clientAppId);
        return service.ensure(principal.getTenantId(), principal.getActorUserId(), clientAppId, form);
    }
}
