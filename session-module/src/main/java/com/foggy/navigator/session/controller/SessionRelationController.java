package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.session.dto.SessionForwardCreateRequest;
import com.foggy.navigator.session.dto.SessionForwardCreateResponse;
import com.foggy.navigator.session.dto.SessionRelationDTO;
import com.foggy.navigator.session.service.SessionForwardService;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/session-relations")
@RequireAuth
@RequiredArgsConstructor
public class SessionRelationController {

    private final SessionForwardService sessionForwardService;

    @PostMapping("/forward")
    public RX<SessionForwardCreateResponse> forwardToNewSession(
            @RequestBody SessionForwardCreateRequest request,
            @RequestHeader(value = "X-Navigator-Client-Request-Id", required = false)
            String clientRequestId) {
        if (clientRequestId != null && clientRequestId.isBlank()) {
            throw new IllegalArgumentException(
                    "X_NAVIGATOR_CLIENT_REQUEST_ID_BLANK");
        }
        return RX.ok(sessionForwardService.forwardToNewSession(
                request,
                UserContext.getCurrentUserId(),
                UserContext.getCurrentTenantId(),
                clientRequestId
        ));
    }

    @GetMapping("/forward/incoming/{targetSessionId}")
    public RX<SessionRelationDTO> findIncomingForwardRelation(@PathVariable String targetSessionId) {
        return RX.ok(sessionForwardService.findIncomingForwardRelation(
                targetSessionId,
                UserContext.getCurrentUserId(),
                UserContext.getCurrentTenantId()
        ));
    }
}
