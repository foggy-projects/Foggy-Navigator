package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.UpstreamAdminCredentialClaimDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapRequestCreatedDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapRequestDTO;
import com.foggy.navigator.business.agent.model.form.ClaimUpstreamAdminCredentialForm;
import com.foggy.navigator.business.agent.model.form.CreateUpstreamBootstrapRequestForm;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/upstream-bootstrap/admin-key-requests")
@RequiredArgsConstructor
public class UpstreamBootstrapRequestController {

    private final UpstreamBootstrapRequestService requestService;

    @PostMapping
    public RX<UpstreamBootstrapRequestCreatedDTO> createRequest(HttpServletRequest request,
                                                                @RequestBody CreateUpstreamBootstrapRequestForm form) {
        return RX.ok(requestService.createRequest(form, request == null ? null : request.getRemoteAddr()));
    }

    @GetMapping("/{requestCode}/status")
    public RX<UpstreamBootstrapRequestDTO> getStatus(@PathVariable String requestCode) {
        return RX.ok(requestService.getPublicStatus(requestCode));
    }

    @PostMapping("/{requestCode}/claim")
    public RX<UpstreamAdminCredentialClaimDTO> claim(@PathVariable String requestCode,
                                                     @RequestBody ClaimUpstreamAdminCredentialForm form) {
        return RX.ok(requestService.claim(requestCode, form));
    }
}
