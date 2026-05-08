package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BizWorkerIdentityDTO;
import com.foggy.navigator.business.agent.model.dto.BizWorkerPoolDTO;
import com.foggy.navigator.business.agent.model.form.AddWorkerPoolMemberForm;
import com.foggy.navigator.business.agent.model.form.CreateWorkerPoolForm;
import com.foggy.navigator.business.agent.model.form.RegisterWorkerIdentityForm;
import com.foggy.navigator.business.agent.model.form.UpdateStatusForm;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/business-agent")
@RequiredArgsConstructor
public class BizWorkerPoolController {

    private String resolveTenantId() {
        com.foggy.navigator.common.dto.CurrentUser user = UserContext.getCurrentUser();
        String tenantId = user.getTenantId();
        return (tenantId != null && !tenantId.isEmpty()) ? tenantId : user.getUserId();
    }


    private final BizWorkerPoolService workerPoolService;

    @PostMapping("/worker-identities")
    @RequireAuth(roles = {"SUPER_ADMIN"})
    public RX<BizWorkerIdentityDTO> registerWorkerIdentity(@RequestBody RegisterWorkerIdentityForm form) {
        return RX.ok(workerPoolService.registerWorkerIdentity(form));
    }

    @GetMapping("/worker-pools")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<List<BizWorkerPoolDTO>> listPools() {
        return RX.ok(workerPoolService.listPools(resolveTenantId()));
    }

    @PostMapping("/worker-pools")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<BizWorkerPoolDTO> createPool(@RequestBody CreateWorkerPoolForm form) {
        return RX.ok(workerPoolService.createPool(resolveTenantId(), form));
    }

    @PostMapping("/worker-pools/{poolId}/members")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<Void> addMember(@PathVariable String poolId,
                              @RequestBody AddWorkerPoolMemberForm form) {
        workerPoolService.addMember(resolveTenantId(), poolId, form);
        return RX.ok(null);
    }

    @PutMapping("/worker-pools/{poolId}/status")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<BizWorkerPoolDTO> updatePoolStatus(@PathVariable String poolId,
                                                 @RequestBody UpdateStatusForm form) {
        return RX.ok(workerPoolService.updatePoolStatus(
                resolveTenantId(), poolId, form == null ? null : form.getStatus()));
    }
}
