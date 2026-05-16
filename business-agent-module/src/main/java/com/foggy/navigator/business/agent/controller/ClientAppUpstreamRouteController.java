package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.dto.ClientAppUpstreamRouteDTO;
import com.foggy.navigator.business.agent.model.form.UpsertClientAppUpstreamRouteForm;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggy.navigator.business.agent.service.ClientAppUpstreamRouteService;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/business-agent/client-apps/{clientAppId}/upstream-routes")
@RequiredArgsConstructor
public class ClientAppUpstreamRouteController {

    private final ClientAppUpstreamRouteService routeService;
    private final ClientAppControlCredentialService controlCredentialService;

    @GetMapping
    public RX<List<ClientAppUpstreamRouteDTO>> listRoutes(HttpServletRequest request,
                                                          @PathVariable String clientAppId) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(routeService.listRoutes(principal.getTenantId(), clientAppId));
    }

    @PutMapping("/{upstreamRef}")
    public RX<ClientAppUpstreamRouteDTO> upsertRoute(HttpServletRequest request,
                                                     @PathVariable String clientAppId,
                                                     @PathVariable String upstreamRef,
                                                     @RequestBody UpsertClientAppUpstreamRouteForm form) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(routeService.upsertRoute(
                principal.getTenantId(), clientAppId, upstreamRef, principal.getActorUserId(), form));
    }

    @PutMapping("/{upstreamRef}/status")
    public RX<ClientAppUpstreamRouteDTO> updateStatus(HttpServletRequest request,
                                                      @PathVariable String clientAppId,
                                                      @PathVariable String upstreamRef,
                                                      @RequestParam String status) {
        ClientAppControlPlanePrincipal principal = requireAccess(request, clientAppId);
        return RX.ok(routeService.updateStatus(principal.getTenantId(), clientAppId, upstreamRef, status));
    }

    private ClientAppControlPlanePrincipal requireAccess(HttpServletRequest request, String clientAppId) {
        return controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_UPSTREAM_ROUTE_MANAGE, clientAppId);
    }
}
