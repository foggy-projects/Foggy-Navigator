package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.UpstreamAdminCredentialDTO;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/upstream-admin/admin-credential")
@RequiredArgsConstructor
public class UpstreamAdminCredentialSelfController {

    private final UpstreamClientAppAdminCredentialService adminCredentialService;

    @GetMapping("/current")
    public RX<UpstreamAdminCredentialDTO> current(HttpServletRequest request) {
        return RX.ok(adminCredentialService.inspectCurrentCredential(request));
    }
}
