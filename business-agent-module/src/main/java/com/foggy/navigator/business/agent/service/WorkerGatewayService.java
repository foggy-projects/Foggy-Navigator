package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionSummaryDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayFunctionListDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayFunctionSchemaDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayFunctionSummaryDTO;
import com.foggy.navigator.business.agent.model.dto.WorkerGatewayInvokeResponseDTO;
import com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterInvoker;
import com.foggy.navigator.business.agent.service.adapter.BusinessFunctionAdapterResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerGatewayService {

    private final BusinessAgentTaskService taskService;
    private final BusinessFunctionAuthorizationService authorizationService;
    private final BusinessFunctionRegistryService functionRegistryService;
    private final SkillRegistryService skillRegistryService;
    private final ClientAppUserGrantService userGrantService;
    private final BusinessFunctionSuspensionService suspensionService;
    private final BusinessFunctionAdapterInvoker adapterInvoker;
    private final ObjectMapper objectMapper;
    private final BusinessFunctionRuntimeAuditService auditService;

    @Transactional(readOnly = true)
    public WorkerGatewayFunctionListDTO listBusinessFunctions(String tokenStr, String domain, String riskLevel) {
        BusinessTaskScopedTokenDTO token = taskService.resolveTaskScopedToken(tokenStr);
        requireCompleteToken(token);

        // Pre-validate App/User/Skill grants to ensure the session is active before returning any lists
        userGrantService.checkUpstreamUserAccess(token.getTenantId(), token.getClientAppId(), token.getUpstreamUserId());
        skillRegistryService.checkClientAppSkillAccess(token.getTenantId(), token.getClientAppId(), token.getSkillId());

        // Fetch client app visible functions
        List<BusinessFunctionSummaryDTO> appFunctions = functionRegistryService.listClientAppVisibleFunctionSummaries(
                token.getTenantId(), token.getClientAppId());

        // Runtime visibility follows ClientApp function grants. Skill allowlist is
        // only a materialization/recommendation hint and is not a hard gate here.
        List<WorkerGatewayFunctionSummaryDTO> summaries = appFunctions.stream()
                .filter(f -> !StringUtils.hasText(domain) || domain.equals(f.getDomain()))
                .filter(f -> !StringUtils.hasText(riskLevel) || riskLevel.equals(f.getRiskLevel()))
                .map(f -> {
                    WorkerGatewayFunctionSummaryDTO summary = new WorkerGatewayFunctionSummaryDTO();
                    summary.setFunctionId(f.getFunctionId());
                    summary.setVersion(f.getVersion());
                    summary.setDomain(f.getDomain());
                    summary.setName(f.getName());
                    summary.setDescription(f.getDescription());
                    summary.setRiskLevel(f.getRiskLevel());
                    summary.setApprovalRequired(f.getApprovalRequired());
                    summary.setLlmVisibleSummary(f.getLlmVisibleSummary());
                    summary.setSchemaVisibleSummary(f.getSchemaVisibleSummary());
                    return summary;
                })
                .collect(Collectors.toList());

        WorkerGatewayFunctionListDTO result = new WorkerGatewayFunctionListDTO();
        result.setFunctions(summaries);
        return result;
    }

    @Transactional(readOnly = true)
    public WorkerGatewayFunctionSchemaDTO getBusinessFunctionSchema(String tokenStr, String functionId, String version) {
        BusinessTaskScopedTokenDTO token = taskService.resolveTaskScopedToken(tokenStr);
        requireCompleteToken(token);

        // This method does a full fail-closed authorization check
        BusinessFunctionRuntimeContextDTO context = authorizationService.resolveExecutableBusinessFunction(
                token.getTenantId(),
                token.getClientAppId(),
                token.getUpstreamUserId(),
                token.getSkillId(),
                functionId,
                version
        );

        WorkerGatewayFunctionSchemaDTO schemaDTO = new WorkerGatewayFunctionSchemaDTO();
        schemaDTO.setFunctionId(context.getFunction().getFunctionId());
        schemaDTO.setVersion(context.getVersionData().getVersion());
        schemaDTO.setInputSchemaJson(context.getVersionData().getInputSchemaJson());
        schemaDTO.setOutputSchemaJson(context.getVersionData().getOutputSchemaJson());
        schemaDTO.setRiskLevel(context.getFunction().getRiskLevel());
        schemaDTO.setApprovalRequired(context.getFunction().getApprovalRequired());
        schemaDTO.setLlmVisibleSummary(context.getVersionData().getLlmVisibleSummary());
        schemaDTO.setSchemaVisibleSummary(context.getVersionData().getSchemaVisibleSummary());

        // Explicitly NOT copying adapterConfigJson, manifestJson, or transport details.

        return schemaDTO;
    }

    @Transactional
    public WorkerGatewayInvokeResponseDTO invokeBusinessFunction(String tokenStr, String functionId, com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(form.getVersion(), "version is required");
        if (!StringUtils.hasText(form.getInputJson()) && form.getInput() == null) {
            throw new IllegalArgumentException("inputJson or input is required");
        }

        BusinessTaskScopedTokenDTO token = taskService.resolveTaskScopedToken(tokenStr);
        requireCompleteToken(token);

        // This method does a full fail-closed authorization check
        BusinessFunctionRuntimeContextDTO context = authorizationService.resolveExecutableBusinessFunction(
                token.getTenantId(),
                token.getClientAppId(),
                token.getUpstreamUserId(),
                token.getSkillId(),
                functionId,
                form.getVersion()
        );
        attachTokenContext(context, token);

        // Normalize inputJson
        String finalInputJson = form.getInputJson();
        if (!StringUtils.hasText(finalInputJson) && form.getInput() != null) {
            try {
                finalInputJson = objectMapper.writeValueAsString(form.getInput());
            } catch (Exception e) {
                log.error("Failed to serialize form.input to JSON", e);
                throw new IllegalArgumentException("Invalid structured input: serialization failed", e);
            }
        }

        // Audit: invoke started (best-effort)
        String inputHash = BusinessFunctionRuntimeAuditService.sha256(finalInputJson);
        auditService.recordInvokeStarted(token, functionId, form.getVersion(), inputHash);
        long startTime = System.currentTimeMillis();

        WorkerGatewayInvokeResponseDTO response = new WorkerGatewayInvokeResponseDTO();
        response.setFunctionId(context.getFunction().getFunctionId());
        response.setVersion(context.getVersionData().getVersion());
        response.setApprovalRequired(context.getFunction().getApprovalRequired());

        if (Boolean.TRUE.equals(context.getFunction().getApprovalRequired())) {
            com.foggy.navigator.business.agent.model.entity.BusinessFunctionSuspensionEntity suspension = suspensionService.createSuspension(token, functionId, form.getVersion(), finalInputJson, form.getIdempotencyKey());
            response.setStatus(WorkerGatewayInvokeResponseDTO.STATUS_SUSPENDED);
            response.setSuspendId(suspension.getSuspendId());
            response.setMessage("Approval required, execution suspended.");
            // Audit: invoke suspended (best-effort)
            auditService.recordInvokeSuspended(token, functionId, form.getVersion(), suspension.getSuspendId());
        } else {
            try {
                BusinessFunctionAdapterResult adapterResult = adapterInvoker.invoke(context, finalInputJson);
                if (adapterResult == null) {
                    throw new IllegalArgumentException("Adapter execution returned no result");
                }
                if (!BusinessFunctionAdapterResult.STATUS_SUCCESS.equals(adapterResult.getStatus())) {
                    String message = StringUtils.hasText(adapterResult.getMessage())
                            ? adapterResult.getMessage()
                            : "Adapter execution failed";
                    throw new IllegalArgumentException(message);
                }
                response.setStatus(WorkerGatewayInvokeResponseDTO.STATUS_SUCCESS);
                response.setOutputJson(adapterResult.getOutputJson());
                response.setMessage(adapterResult.getMessage());
                // Audit: invoke success (best-effort)
                long durationMs = System.currentTimeMillis() - startTime;
                String outputHash = BusinessFunctionRuntimeAuditService.sha256(adapterResult.getOutputJson());
                auditService.recordInvokeSuccess(token, functionId, form.getVersion(), outputHash, durationMs);
            } catch (Exception e) {
                // Audit: invoke failed (best-effort)
                long durationMs = System.currentTimeMillis() - startTime;
                auditService.recordInvokeFailed(token, functionId, form.getVersion(), "ADAPTER_ERROR", e.getMessage(), durationMs);
                throw e;
            }
        }

        return response;
    }

    /**
     * Accept a tool execution message from the Worker for audit logging.
     * Validates the task-scoped token to ensure the message comes from a legitimate Worker context.
     */
    @Transactional
    public com.foggy.navigator.business.agent.model.dto.WorkerGatewayToolMessageResponseDTO reportToolMessage(
            String tokenStr,
            com.foggy.navigator.business.agent.model.form.WorkerGatewayToolMessageForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }

        BusinessTaskScopedTokenDTO token = taskService.resolveTaskScopedToken(tokenStr);
        requireCompleteToken(token);

        log.info("Tool message received: tool={}, functionId={}, status={}, suspendId={}, taskId={}, tenantId={}",
                form.getToolName(), form.getFunctionId(), form.getStatus(),
                form.getSuspendId(), token.getTaskId(), token.getTenantId());

        // Audit: tool message (best-effort)
        auditService.recordToolMessage(token, form.getToolName(), form.getFunctionId(), form.getStatus(), form.getMessage());

        return com.foggy.navigator.business.agent.model.dto.WorkerGatewayToolMessageResponseDTO.accepted();
    }

    private void requireCompleteToken(BusinessTaskScopedTokenDTO token) {
        requireText(token.getTenantId(), "token missing tenantId");
        requireText(token.getClientAppId(), "token missing clientAppId");
        requireText(token.getUpstreamUserId(), "token missing upstreamUserId");
        requireText(token.getSkillId(), "token missing skillId");
        requireText(token.getTaskId(), "token missing taskId");
        requireText(token.getSessionId(), "token missing sessionId");
        requireText(token.getWorkerPoolId(), "token missing workerPoolId");
    }

    private void attachTokenContext(BusinessFunctionRuntimeContextDTO context, BusinessTaskScopedTokenDTO token) {
        context.setClientAppId(token.getClientAppId());
        context.setUpstreamUserId(token.getUpstreamUserId());
        context.setSkillId(token.getSkillId());
        context.setTaskId(token.getTaskId());
        context.setSessionId(token.getSessionId());
        context.setWorkerPoolId(token.getWorkerPoolId());
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
