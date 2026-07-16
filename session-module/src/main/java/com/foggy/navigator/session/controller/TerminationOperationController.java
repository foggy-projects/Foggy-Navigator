package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.session.dto.TerminationOperationDTO;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
import com.foggy.navigator.session.service.TerminationOperationService;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Query-only task lifecycle audit surface. */
@RestController
@RequiredArgsConstructor
public class TerminationOperationController {

    private final SessionTaskResourceAccessService accessService;
    private final TerminationOperationService operationService;

    @RequireAuth
    @GetMapping("/api/v1/tasks/{taskId}/termination-operations")
    public RX<List<TerminationOperationDTO>> list(@PathVariable String taskId) {
        CurrentUser user = UserContext.getCurrentUser();
        try {
            accessService.requireOwnedTask(taskId, user.getUserId(), user.getTenantId());
            return RX.ok(operationService.findOwned(taskId, user.getUserId(), user.getTenantId()));
        } catch (RuntimeException ignored) {
            return RX.failB("Termination operation not available");
        }
    }
}
