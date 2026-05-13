package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.E2eModelConfigEnsureResultDTO;
import com.foggy.navigator.business.agent.model.form.EnsureE2eModelConfigForm;
import com.foggy.navigator.business.agent.service.E2eModelConfigEnsureService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/business-agent/client-apps/{clientAppId}/e2e-model-config")
@RequiredArgsConstructor
public class E2eModelConfigController {

    private final E2eModelConfigEnsureService service;

    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping("/ensure")
    public E2eModelConfigEnsureResultDTO ensure(@PathVariable String clientAppId,
                                                @RequestBody EnsureE2eModelConfigForm form) {
        return service.ensure(UserContext.getCurrentTenantId(), UserContext.getCurrentUserId(), clientAppId, form);
    }
}
