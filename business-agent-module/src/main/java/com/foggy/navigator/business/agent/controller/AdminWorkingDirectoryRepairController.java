package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapApprovalActor;
import com.foggy.navigator.business.agent.model.dto.WorkingDirectoryRepairResultDTO;
import com.foggy.navigator.business.agent.model.form.RepairUpstreamSystemWorkingDirectoryForm;
import com.foggy.navigator.business.agent.service.NaviOperatorCredentialService;
import com.foggy.navigator.business.agent.service.WorkingDirectoryAdminRepairService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/working-directories")
@RequiredArgsConstructor
public class AdminWorkingDirectoryRepairController {

    private final NaviOperatorCredentialService operatorCredentialService;
    private final WorkingDirectoryAdminRepairService repairService;

    @PostMapping("/{directoryId}/repair-upstream-system")
    public RX<WorkingDirectoryRepairResultDTO> repairUpstreamSystemDirectory(
            HttpServletRequest request,
            @PathVariable String directoryId,
            @RequestBody RepairUpstreamSystemWorkingDirectoryForm form) {
        UpstreamBootstrapApprovalActor actor = operatorCredentialService.requireAdminOrOperator(request);
        if (!actor.isOperator() && !actor.isSuperAdmin()) {
            throw new SecurityException("navigator operator or super admin credential is required");
        }
        return RX.ok(repairService.repairUpstreamSystemDirectory(directoryId, form));
    }
}
