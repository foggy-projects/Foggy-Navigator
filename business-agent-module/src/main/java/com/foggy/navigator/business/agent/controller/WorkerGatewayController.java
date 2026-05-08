package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.WorkerGatewayFunctionListDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayFunctionSchemaDTO;
import com.foggy.navigator.business.agent.service.WorkerGatewayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/worker-gateway/v1")
@RequiredArgsConstructor
public class WorkerGatewayController {

    private final WorkerGatewayService workerGatewayService;

    @GetMapping("/business-functions")
    public WorkerGatewayFunctionListDTO listBusinessFunctions(
            @RequestHeader("X-Task-Scoped-Token") String tokenStr,
            @RequestHeader(value = "X-Worker-Id", required = false) String workerId,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String riskLevel) {
        return workerGatewayService.listBusinessFunctions(tokenStr, domain, riskLevel);
    }

    @GetMapping("/business-functions/{functionId}/schema")
    public WorkerGatewayFunctionSchemaDTO getBusinessFunctionSchema(
            @RequestHeader("X-Task-Scoped-Token") String tokenStr,
            @PathVariable String functionId,
            @RequestParam String version) {
        return workerGatewayService.getBusinessFunctionSchema(tokenStr, functionId, version);
    }

    @PostMapping("/business-functions/{functionId}/invoke")
    public com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO invokeBusinessFunction(
            @RequestHeader("X-Task-Scoped-Token") String tokenStr,
            @PathVariable String functionId,
            @RequestBody com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form) {
        return workerGatewayService.invokeBusinessFunction(tokenStr, functionId, form);
    }

    @PostMapping("/tool-messages")
    public com.foggy.navigator.business.agent.model.dto.WorkerGatewayToolMessageResponseDTO reportToolMessage(
            @RequestHeader("X-Task-Scoped-Token") String tokenStr,
            @RequestBody com.foggy.navigator.business.agent.model.form.WorkerGatewayToolMessageForm form) {
        return workerGatewayService.reportToolMessage(tokenStr, form);
    }
}
