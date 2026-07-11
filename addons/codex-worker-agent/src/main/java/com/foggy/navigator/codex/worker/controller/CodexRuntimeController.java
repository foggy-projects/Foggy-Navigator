package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeAvailabilityDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeRateLimitsDTO;
import com.foggy.navigator.codex.worker.model.form.CodexRuntimeRegistrationForm;
import com.foggy.navigator.codex.worker.model.form.CodexRuntimeRoutingForm;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRegistryService;
import com.foggy.navigator.codex.worker.service.CodexRuntimeRateLimitsService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/codex-runtimes")
@RequireAuth
@RequiredArgsConstructor
public class CodexRuntimeController {

    private final CodexRuntimeRegistryService runtimeRegistryService;
    private final CodexRuntimeRateLimitsService runtimeRateLimitsService;
    private final WorkerManagementFacade workerManagementFacade;

    @PostMapping
    public RX<CodexRuntimeDTO> register(@RequestBody CodexRuntimeRegistrationForm form) {
        String userId = UserContext.getCurrentUserId();
        workerManagementFacade.validatePhysicalWorkerOwnership(userId, form.getWorkerId());
        return RX.ok(runtimeRegistryService.registerRevision(form));
    }

    @GetMapping
    public RX<List<CodexRuntimeDTO>> list(@RequestParam String workerId) {
        String userId = UserContext.getCurrentUserId();
        workerManagementFacade.validatePhysicalWorkerOwnership(userId, workerId);
        return RX.ok(runtimeRegistryService.listByWorker(workerId));
    }

    @GetMapping("/availability")
    public RX<CodexRuntimeAvailabilityDTO> availability(
            @RequestParam String workerId,
            @RequestParam(required = false) String model) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        workerManagementFacade.validateWorkerAccess(userId, tenantId, workerId);
        return RX.ok(runtimeRegistryService.availability(workerId, model));
    }

    @PostMapping("/{runtimeId}/revisions/{revision}/refresh")
    public RX<CodexRuntimeDTO> refresh(@PathVariable String runtimeId, @PathVariable int revision) {
        validateRuntimeOwner(runtimeId, revision);
        return RX.ok(runtimeRegistryService.refreshCapabilities(runtimeId, revision));
    }

    @GetMapping("/{runtimeId}/revisions/{revision}/rate-limits")
    public RX<CodexRuntimeRateLimitsDTO> rateLimits(
            @PathVariable String runtimeId,
            @PathVariable int revision,
            @RequestParam(defaultValue = "false") boolean refresh,
            HttpServletResponse response) {
        validateRuntimeOwner(runtimeId, revision);
        response.setHeader("Cache-Control", "no-store");
        return RX.ok(runtimeRateLimitsService.read(runtimeId, revision, refresh));
    }

    @PostMapping("/{runtimeId}/revisions/{revision}/recover-instance")
    public RX<CodexRuntimeDTO> recoverInstance(@PathVariable String runtimeId,
                                               @PathVariable int revision) {
        validateRuntimeOwner(runtimeId, revision);
        return RX.ok(runtimeRegistryService.recoverInstanceQuarantine(runtimeId, revision));
    }

    @PutMapping("/{runtimeId}/revisions/{revision}/routing")
    public RX<CodexRuntimeDTO> updateRouting(@PathVariable String runtimeId,
                                             @PathVariable int revision,
                                             @RequestBody CodexRuntimeRoutingForm form) {
        validateRuntimeOwner(runtimeId, revision);
        return RX.ok(runtimeRegistryService.updateRouting(runtimeId, revision, form));
    }

    private void validateRuntimeOwner(String runtimeId, int revision) {
        String userId = UserContext.getCurrentUserId();
        String workerId = runtimeRegistryService.ownerWorkerId(runtimeId, revision);
        workerManagementFacade.validatePhysicalWorkerOwnership(userId, workerId);
    }
}
