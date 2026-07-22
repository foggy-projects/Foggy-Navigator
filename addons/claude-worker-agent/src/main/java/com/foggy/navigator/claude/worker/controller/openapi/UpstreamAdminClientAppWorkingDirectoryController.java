package com.foggy.navigator.claude.worker.controller.openapi;

import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.service.UpstreamAdminClientAppScopeService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.claude.worker.model.dto.WorkingDirectoryDTO;
import com.foggy.navigator.claude.worker.model.form.ClientAppDirectoryInitForm;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Separate S1 system-admin facade; the ClientApp control endpoint remains control-key-only. */
@RestController
@RequestMapping("/api/v1/upstream-admin/client-apps/{clientAppId}/scope/directories")
@RequiredArgsConstructor
public class UpstreamAdminClientAppWorkingDirectoryController {

    private final UpstreamAdminClientAppScopeService scopeService;
    private final ClientAppWorkingDirectoryController directoryController;

    @PostMapping("/init")
    public RX<WorkingDirectoryDTO> init(HttpServletRequest request, @PathVariable String clientAppId,
                                        @RequestBody ClientAppDirectoryInitForm form) {
        return directoryController.initDirectory(principal(request, clientAppId), form);
    }

    @GetMapping
    public RX<List<WorkingDirectoryDTO>> list(HttpServletRequest request, @PathVariable String clientAppId,
                                              @RequestParam(required = false) String workerId,
                                              @RequestParam(required = false) WorkspaceScope workspaceScope,
                                              @RequestParam(required = false) String upstreamUserId) {
        return directoryController.listDirectories(principal(request, clientAppId), workerId, workspaceScope, upstreamUserId);
    }

    @GetMapping("/{directoryId}")
    public RX<WorkingDirectoryDTO> get(HttpServletRequest request, @PathVariable String clientAppId,
                                       @PathVariable String directoryId) {
        return directoryController.getDirectory(principal(request, clientAppId), directoryId);
    }

    @DeleteMapping("/{directoryId}")
    public RX<Void> delete(HttpServletRequest request, @PathVariable String clientAppId,
                           @PathVariable String directoryId) {
        return directoryController.deleteDirectory(principal(request, clientAppId), directoryId);
    }

    @PutMapping("/{directoryId}/env")
    public RX<Map<String, String>> env(HttpServletRequest request, @PathVariable String clientAppId,
                                       @PathVariable String directoryId, @RequestBody Map<String, String> envVars) {
        return directoryController.updateDirectoryEnvVars(principal(request, clientAppId), directoryId, envVars);
    }

    @PutMapping("/{directoryId}/files")
    public RX<Map<String, Object>> files(HttpServletRequest request, @PathVariable String clientAppId,
                                         @PathVariable String directoryId, @RequestBody Map<String, String> files) {
        return directoryController.updateDirectoryFiles(principal(request, clientAppId), directoryId, files);
    }

    private ClientAppControlPlanePrincipal principal(HttpServletRequest request, String clientAppId) {
        return scopeService.requireScope(request, clientAppId,
                UpstreamBootstrapRequestService.SCOPE_WORKING_DIRECTORY_MANAGE).principal();
    }
}
