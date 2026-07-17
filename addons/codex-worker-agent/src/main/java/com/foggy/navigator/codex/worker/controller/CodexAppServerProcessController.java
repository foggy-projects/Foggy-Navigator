package com.foggy.navigator.codex.worker.controller;

import com.foggy.navigator.codex.worker.service.CodexAppServerProcessService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Control-plane proxy for safe App Server runtime process observation. */
@RestController
@RequestMapping("/api/v1/codex-app-server-workers")
@RequireAuth
@RequiredArgsConstructor
public class CodexAppServerProcessController {

    private final WorkerManagementFacade workerManagementFacade;
    private final CodexAppServerProcessService processService;

    @GetMapping("/{workerId}/processes")
    public RX<Map<String, Object>> listProcesses(@PathVariable String workerId) {
        workerManagementFacade.validatePhysicalWorkerOwnership(UserContext.getCurrentUserId(), workerId);
        return RX.ok(processService.listProcesses(workerId));
    }
}
