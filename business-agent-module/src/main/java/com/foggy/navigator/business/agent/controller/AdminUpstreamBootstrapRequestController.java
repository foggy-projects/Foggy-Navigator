package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapApprovalActor;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapRequestDTO;
import com.foggy.navigator.business.agent.model.form.ApproveUpstreamBootstrapRequestForm;
import com.foggy.navigator.business.agent.model.form.DenyUpstreamBootstrapRequestForm;
import com.foggy.navigator.business.agent.service.NaviOperatorCredentialService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/upstream-bootstrap-requests")
@RequiredArgsConstructor
public class AdminUpstreamBootstrapRequestController {

    private final UpstreamBootstrapRequestService requestService;
    private final NaviOperatorCredentialService operatorCredentialService;

    @GetMapping
    public RX<List<UpstreamBootstrapRequestDTO>> listRequests(HttpServletRequest request,
                                                              @RequestParam(required = false) String status) {
        UpstreamBootstrapApprovalActor actor = operatorCredentialService.requireAdminOrOperator(request);
        return RX.ok(requestService.listRequests(status, actor));
    }

    @GetMapping("/{requestCode}")
    public RX<UpstreamBootstrapRequestDTO> getRequest(HttpServletRequest request,
                                                      @PathVariable String requestCode) {
        UpstreamBootstrapApprovalActor actor = operatorCredentialService.requireAdminOrOperator(request);
        return RX.ok(requestService.getRequest(requestCode, actor));
    }

    @PostMapping("/{requestCode}/approve")
    public RX<UpstreamBootstrapRequestDTO> approve(HttpServletRequest request,
                                                   @PathVariable String requestCode,
                                                   @RequestBody ApproveUpstreamBootstrapRequestForm form) {
        UpstreamBootstrapApprovalActor actor = operatorCredentialService.requireAdminOrOperator(request);
        return RX.ok(requestService.approve(requestCode, form, actor));
    }

    @PostMapping("/{requestCode}/deny")
    public RX<UpstreamBootstrapRequestDTO> deny(HttpServletRequest request,
                                                @PathVariable String requestCode,
                                                @RequestBody DenyUpstreamBootstrapRequestForm form) {
        UpstreamBootstrapApprovalActor actor = operatorCredentialService.requireAdminOrOperator(request);
        return RX.ok(requestService.deny(requestCode, form, actor));
    }
}
