package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.WorkerGatewayResumeResponseDTO;
import com.foggy.navigator.business.agent.model.form.WorkerGatewayResumeForm;
import com.foggy.navigator.business.agent.service.BusinessFunctionSuspensionService;
import com.foggy.navigator.common.annotation.RequireAuth;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/business-agent/suspensions")
@RequiredArgsConstructor
public class BusinessFunctionApprovalController {

    private final BusinessFunctionSuspensionService suspensionService;

    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping("/{suspendId}/resume")
    public WorkerGatewayResumeResponseDTO resumeSuspension(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String userId,
            @PathVariable String suspendId,
            @RequestBody WorkerGatewayResumeForm form) {

        suspensionService.resumeSuspension(tenantId, userId, suspendId, form);

        WorkerGatewayResumeResponseDTO response = new WorkerGatewayResumeResponseDTO();
        response.setStatus("resume_dispatched");
        response.setSuspendId(suspendId);
        response.setResumeRef("resume_" + suspendId);

        return response;
    }
}
