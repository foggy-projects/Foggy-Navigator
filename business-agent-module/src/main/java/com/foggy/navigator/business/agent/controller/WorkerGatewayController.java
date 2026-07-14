package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.WorkerGatewayFunctionListDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayFunctionSchemaDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.service.WorkerGatewayRequestAuthorizationService;
import com.foggy.navigator.business.agent.service.WorkerGatewayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/worker-gateway/v1")
@RequiredArgsConstructor
public class WorkerGatewayController {

    private final WorkerGatewayService workerGatewayService;
    private final WorkerGatewayRequestAuthorizationService requestAuthorizationService;

    @GetMapping("/business-functions")
    public WorkerGatewayFunctionListDTO listBusinessFunctions(
            @RequestHeader("X-Task-Scoped-Token") String tokenStr,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_ID, required = false)
            String workerId,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_CREDENTIAL, required = false)
            String workerCredential,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_LEASE_ID, required = false)
            String workerLeaseId,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.LEGACY_HEADER_WORKER_ID, required = false)
            String legacyWorkerId,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String riskLevel) {
        BusinessTaskScopedTokenDTO token = requestAuthorizationService.authorize(
                tokenStr, workerId, workerCredential, workerLeaseId, legacyWorkerId);
        return workerGatewayService.listBusinessFunctions(token, domain, riskLevel);
    }

    @GetMapping("/business-functions/{functionId}/schema")
    public WorkerGatewayFunctionSchemaDTO getBusinessFunctionSchema(
            @RequestHeader("X-Task-Scoped-Token") String tokenStr,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_ID, required = false)
            String workerId,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_CREDENTIAL, required = false)
            String workerCredential,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_LEASE_ID, required = false)
            String workerLeaseId,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.LEGACY_HEADER_WORKER_ID, required = false)
            String legacyWorkerId,
            @PathVariable String functionId,
            @RequestParam(required = false) String version) {
        BusinessTaskScopedTokenDTO token = requestAuthorizationService.authorize(
                tokenStr, workerId, workerCredential, workerLeaseId, legacyWorkerId);
        return workerGatewayService.getBusinessFunctionSchema(token, functionId, version);
    }

    @PostMapping("/business-functions/{functionId}/invoke")
    public com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO invokeBusinessFunction(
            @RequestHeader("X-Task-Scoped-Token") String tokenStr,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_ID, required = false)
            String workerId,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_CREDENTIAL, required = false)
            String workerCredential,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_LEASE_ID, required = false)
            String workerLeaseId,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.LEGACY_HEADER_WORKER_ID, required = false)
            String legacyWorkerId,
            @PathVariable String functionId,
            @RequestBody com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form) {
        BusinessTaskScopedTokenDTO token = requestAuthorizationService.authorize(
                tokenStr, workerId, workerCredential, workerLeaseId, legacyWorkerId);
        return workerGatewayService.invokeBusinessFunction(token, functionId, form);
    }

    @PostMapping("/tool-messages")
    public com.foggy.navigator.business.agent.model.dto.WorkerGatewayToolMessageResponseDTO reportToolMessage(
            @RequestHeader("X-Task-Scoped-Token") String tokenStr,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_ID, required = false)
            String workerId,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_CREDENTIAL, required = false)
            String workerCredential,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.HEADER_WORKER_LEASE_ID, required = false)
            String workerLeaseId,
            @RequestHeader(value = WorkerGatewayRequestAuthorizationService.LEGACY_HEADER_WORKER_ID, required = false)
            String legacyWorkerId,
            @RequestBody com.foggy.navigator.business.agent.model.form.WorkerGatewayToolMessageForm form) {
        BusinessTaskScopedTokenDTO token = requestAuthorizationService.authorize(
                tokenStr, workerId, workerCredential, workerLeaseId, legacyWorkerId);
        return workerGatewayService.reportToolMessage(token, form);
    }
}
