package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppDTO;
import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.form.CreateClientAppForm;
import com.foggy.navigator.business.agent.model.form.IssueRuntimeCredentialForm;
import com.foggy.navigator.business.agent.model.form.UpdateStatusForm;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/client-apps")
@RequireAuth(roles = {"TENANT_ADMIN"})
@RequiredArgsConstructor
public class ClientAppController {

    private final ClientAppService clientAppService;

    @GetMapping
    public RX<List<ClientAppDTO>> listClientApps() {
        return RX.ok(clientAppService.listClientApps(UserContext.getCurrentTenantId()));
    }

    @PostMapping
    public RX<ClientAppDTO> createClientApp(@RequestBody CreateClientAppForm form) {
        return RX.ok(clientAppService.createClientApp(
                UserContext.getCurrentTenantId(), UserContext.getCurrentUserId(), form));
    }

    @PostMapping("/{clientAppId}/runtime-credentials")
    public RX<IssuedCredentialDTO> issueRuntimeCredential(@PathVariable String clientAppId,
                                                          @RequestBody IssueRuntimeCredentialForm form) {
        return RX.ok(clientAppService.issueRuntimeCredential(
                UserContext.getCurrentTenantId(), clientAppId, form));
    }

    @PutMapping("/{clientAppId}/status")
    public RX<ClientAppDTO> updateStatus(@PathVariable String clientAppId,
                                         @RequestBody UpdateStatusForm form) {
        return RX.ok(clientAppService.updateStatus(
                UserContext.getCurrentTenantId(), clientAppId, form == null ? null : form.getStatus()));
    }
}
