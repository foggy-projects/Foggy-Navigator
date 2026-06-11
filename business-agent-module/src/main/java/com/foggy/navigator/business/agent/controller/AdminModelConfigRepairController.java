package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapApprovalActor;
import com.foggy.navigator.business.agent.service.NaviOperatorCredentialService;
import com.foggy.navigator.common.dto.LlmModelConfigOwnerRepairResultDTO;
import com.foggy.navigator.common.form.LlmModelConfigOwnerRepairForm;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/model-configs")
@RequiredArgsConstructor
public class AdminModelConfigRepairController {

    private final NaviOperatorCredentialService operatorCredentialService;
    private final LlmModelManager llmModelManager;

    @PostMapping("/{modelConfigId}/repair-owner")
    public RX<LlmModelConfigOwnerRepairResultDTO> repairModelConfigOwner(
            HttpServletRequest request,
            @PathVariable String modelConfigId,
            @RequestBody LlmModelConfigOwnerRepairForm form) {
        UpstreamBootstrapApprovalActor actor = operatorCredentialService.requireAdminOrOperator(request);
        if (!actor.isOperator() && !actor.isSuperAdmin()) {
            throw new SecurityException("navigator operator or super admin credential is required");
        }
        return RX.ok(llmModelManager.repairModelConfigOwner(modelConfigId, form));
    }
}
