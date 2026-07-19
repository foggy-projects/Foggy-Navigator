package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ManagementAuthorizationExplainResponseDTO;
import com.foggy.navigator.business.agent.model.dto.ManagementIssuedTokenResponseDTO;
import com.foggy.navigator.business.agent.model.dto.ManagementPermissionsResponseDTO;
import com.foggy.navigator.business.agent.model.dto.ManagementWhoamiResponseDTO;
import com.foggy.navigator.business.agent.model.form.ManagementAuthorizationExplainForm;
import com.foggy.navigator.business.agent.model.form.ManagementSecurityActionAuthorizeForm;
import com.foggy.navigator.business.agent.service.ManagementAuthEndpointService;
import com.foggy.navigator.common.authorization.AuthorizationReasonCode;
import com.foggy.navigator.common.authorization.ManagementAuthenticationContext;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Typed-management authentication endpoints.
 *
 * <p>The canonical ingress interceptor is the only HTTP credential reader.
 * This controller intentionally reads just the safe request-local context
 * attribute, never headers, bearer material, entities, or repositories.</p>
 */
@RestController
@RequestMapping(ManagementAuthEndpointService.BASE_PATH)
public class ManagementAuthController {

    private static final String AUTH_CONTEXT_ATTRIBUTE = ManagementAuthenticationContext.class.getName();

    private final ManagementAuthEndpointService endpointService;

    public ManagementAuthController(ManagementAuthEndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @PostMapping("/exchange")
    public RX<ManagementIssuedTokenResponseDTO> exchange(HttpServletRequest request) {
        return RX.ok(endpointService.exchange(requireContext(request)));
    }

    @PostMapping("/security-actions/authorize")
    public RX<ManagementIssuedTokenResponseDTO> authorizeSecurityAction(
            HttpServletRequest request,
            @RequestBody ManagementSecurityActionAuthorizeForm form
    ) {
        return RX.ok(endpointService.authorizeSecurityAction(requireContext(request), form));
    }

    @GetMapping("/whoami")
    public RX<ManagementWhoamiResponseDTO> whoami(HttpServletRequest request) {
        return RX.ok(endpointService.whoami(requireContext(request)));
    }

    @GetMapping("/permissions")
    public RX<ManagementPermissionsResponseDTO> permissions(HttpServletRequest request) {
        return RX.ok(endpointService.permissions(requireContext(request)));
    }

    @PostMapping("/explain")
    public RX<ManagementAuthorizationExplainResponseDTO> explain(
            HttpServletRequest request,
            @RequestBody ManagementAuthorizationExplainForm form
    ) {
        return RX.ok(endpointService.explain(requireContext(request), form));
    }

    private static ManagementAuthenticationContext requireContext(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(AUTH_CONTEXT_ATTRIBUTE);
        if (value instanceof ManagementAuthenticationContext context) {
            return context;
        }
        throw new SecurityException("typed management credential is required ("
                + AuthorizationReasonCode.AUTHN_CREDENTIAL_MISSING.name() + ")");
    }
}
