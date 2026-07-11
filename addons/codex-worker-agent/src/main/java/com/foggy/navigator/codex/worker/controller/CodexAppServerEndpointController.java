package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.model.dto.CodexAppServerEndpointDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexAppServerEndpointSyncDTO;
import com.foggy.navigator.codex.worker.model.form.CodexAppServerEndpointForm;
import com.foggy.navigator.codex.worker.service.CodexAppServerEndpointService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/codex-app-server-endpoints")
@RequireAuth
@RequiredArgsConstructor
public class CodexAppServerEndpointController {

    private final CodexAppServerEndpointService endpointService;
    private final WorkerManagementFacade workerManagementFacade;

    @PostMapping
    public RX<CodexAppServerEndpointDTO> create(@RequestBody CodexAppServerEndpointForm form) {
        workerManagementFacade.validatePhysicalWorkerOwnership(
                UserContext.getCurrentUserId(), form.getWorkerId());
        return RX.ok(endpointService.create(form));
    }

    @GetMapping
    public RX<List<CodexAppServerEndpointDTO>> list(@RequestParam String workerId) {
        workerManagementFacade.validatePhysicalWorkerOwnership(UserContext.getCurrentUserId(), workerId);
        return RX.ok(endpointService.listByWorker(workerId));
    }

    @PutMapping("/{endpointId}")
    public RX<CodexAppServerEndpointDTO> update(@PathVariable String endpointId,
                                                 @RequestBody CodexAppServerEndpointForm form) {
        validateEndpointOwner(endpointId);
        return RX.ok(endpointService.update(endpointId, form));
    }

    @DeleteMapping("/{endpointId}")
    public RX<Void> delete(@PathVariable String endpointId) {
        validateEndpointOwner(endpointId);
        endpointService.delete(endpointId);
        return RX.ok();
    }

    @PostMapping("/{endpointId}/sync")
    public RX<CodexAppServerEndpointSyncDTO> synchronize(@PathVariable String endpointId) {
        validateEndpointOwner(endpointId);
        return RX.ok(endpointService.synchronize(endpointId));
    }

    private void validateEndpointOwner(String endpointId) {
        workerManagementFacade.validatePhysicalWorkerOwnership(
                UserContext.getCurrentUserId(), endpointService.ownerWorkerId(endpointId));
    }
}
