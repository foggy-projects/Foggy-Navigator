package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.session.dto.SessionForwardCreateRequest;
import com.foggy.navigator.session.dto.SessionForwardCreateResponse;
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
    public RX<SessionForwardCreateResponse> forwardToNewSession(@RequestBody SessionForwardCreateRequest request) {
        return RX.ok(sessionForwardService.forwardToNewSession(
                request,
                UserContext.getCurrentUserId(),
                UserContext.getCurrentTenantId()
        ));
    }
}
