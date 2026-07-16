package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.business.agent.service.BizWorkerPoolWorkerSelector;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLauncher;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CodexBusinessAgentWorkerTaskLauncher implements BusinessAgentWorkerTaskLauncher {

    private final BizWorkerPoolWorkerSelector poolWorkerSelector;
    private final CodexBizTaskProvider codexBizTaskProvider;

    @Override
    public String getWorkerBackend() {
        return ClientAppModelConfigGrantService.OPENAI_CODEX_BACKEND;
    }

    @Override
    public String resolveWorkerId(BusinessAgentWorkerTaskLaunchRequest request) {
        return resolveWorkerId(request, request.getPhysicalWorkerId());
    }

    @Override
    public BusinessAgentWorkerTaskLaunchResult launch(BusinessAgentWorkerTaskLaunchRequest request) {
        String selectedWorkerId = requireText(
                request.getSelectedWorkerId(), "selectedWorkerId is required before launch");
        requireText(request.getWorkerLeaseId(), "workerLeaseId is required before launch");
        String workerId = resolveWorkerId(request, selectedWorkerId);
        if (!selectedWorkerId.equals(workerId)) {
            throw new SecurityException("resolved worker changed before launch");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        putText(params, "agentId", request.getAgentId());
        putText(params, "workerId", workerId);
        putText(params, "sessionId", request.getSessionId());
        putText(params, "contextId", request.getContextId());
        putText(params, "modelConfigId", request.getModelConfigId());
        putText(params, "model", request.getModel());
        putText(params, "directoryId", request.getDirectoryId());
        putText(params, "cwd", request.getWorkdir());
        putText(params, "providerType", CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        putText(params, "codexHomeKey", resolveCodexAccountKey(request));
        putText(params, "privateAccountId", resolveCodexAccountKey(request));
        params.put("prompt", "Business Agent task " + request.getBusinessTaskId()
                + ". Use the business function tools when user intent requires controlled business execution.");
        params.put("developerInstructions", buildDeveloperInstructions(request));
        Map<String, Object> runtimeContext = buildBusinessRuntimeContext(request);
        if (!runtimeContext.isEmpty()) {
            params.put("businessRuntimeContext", runtimeContext);
        }
        putStringList(params, "additionalDirectories", request.getAllowedDirs());

        DispatchTaskDTO workerTask = codexBizTaskProvider.createTaskDirect(
                params, request.getActorUserId(), request.getTenantId());
        if (workerTask == null
                || !StringUtils.hasText(workerTask.getWorkerId())
                || !workerId.equals(workerTask.getWorkerId().trim())) {
            throw new SecurityException("Codex task was created on a different worker");
        }
        return BusinessAgentWorkerTaskLaunchResult.builder()
                .workerTaskId(workerTask.getTaskId())
                .workerSessionId(workerTask.getSessionId())
                .contextId(firstNonBlank(workerTask.getContextId(), request.getContextId()))
                .workerId(workerId)
                .providerType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE)
                .build();
    }

    private String resolveWorkerId(
            BusinessAgentWorkerTaskLaunchRequest request,
            String requestedWorkerId) {
        requireCodexBackend(request);
        String routeId = requireText(request.getWorkerPoolId(), "workerPoolId is required");
        if (request.getWorkerPoolOwnerType() == null) {
            String physicalWorkerId = requireText(
                    requestedWorkerId, "physicalWorkerId is required when worker pool is absent");
            if (!routeId.equals(physicalWorkerId)) {
                throw new IllegalStateException("worker pool owner is missing for pooled route: " + routeId);
            }
            return physicalWorkerId;
        }

        return poolWorkerSelector.resolveEnabledWorkerId(
                requireText(request.getTenantId(), "tenantId is required"),
                request.getWorkerPoolOwnerType(),
                requireText(request.getWorkerPoolOwnerId(), "workerPoolOwnerId is required"),
                routeId,
                getWorkerBackend(),
                requestedWorkerId);
    }

    private String resolveCodexAccountKey(BusinessAgentWorkerTaskLaunchRequest request) {
        String accountPart = firstNonBlank(request.getUpstreamUserId(), request.getBusinessTaskId(), request.getActorUserId());
        return String.join("/",
                sanitizeKeyPart(firstNonBlank(request.getTenantId(), "default")),
                sanitizeKeyPart(firstNonBlank(request.getClientAppId(), "default")),
                sanitizeKeyPart(firstNonBlank(accountPart, "default")));
    }

    private String buildDeveloperInstructions(BusinessAgentWorkerTaskLaunchRequest request) {
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        StringBuilder builder = new StringBuilder();
        builder.append("You are running as a Navigator Business Agent worker.\n");
        builder.append("Use business function tools only when the user intent requires controlled business execution.\n");
        appendLine(builder, "business_task_id", request.getBusinessTaskId());
        appendLine(builder, "context_id", request.getContextId());
        appendLine(builder, "session_id", request.getSessionId());
        appendLine(builder, "client_app_id", request.getClientAppId());
        appendLine(builder, "upstream_user_id", request.getUpstreamUserId());
        appendLine(builder, "business_agent_id", request.getAgentId());
        appendLine(builder, "business_skill_id", request.getSkillId());
        appendLine(builder, "business_skill_name", resolveSkillName(request));
        appendLine(builder, "directory_id", request.getDirectoryId());
        appendLine(builder, "workdir", request.getWorkdir());
        appendList(builder, "allowed_dirs", request.getAllowedDirs());
        appendList(builder, "allowed_tools", request.getAllowedTools());
        appendLine(builder, "current_time", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        appendLine(builder, "timezone", zoneId.getId());
        appendLine(builder, "business_date", now.toLocalDate().toString());
        return builder.toString();
    }

    private Map<String, Object> buildBusinessRuntimeContext(BusinessAgentWorkerTaskLaunchRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        putText(context, "business_task_id", request.getBusinessTaskId());
        putText(context, "context_id", request.getContextId());
        putText(context, "session_id", request.getSessionId());
        putText(context, "client_app_id", request.getClientAppId());
        putText(context, "upstream_user_id", request.getUpstreamUserId());
        putText(context, "business_agent_id", request.getAgentId());
        putText(context, "business_skill_id", request.getSkillId());
        putText(context, "business_skill_name", resolveSkillName(request));
        putText(context, "directory_id", request.getDirectoryId());
        putText(context, "workdir", request.getWorkdir());
        putText(context, "task_scoped_token", request.getTaskScopedToken());
        putText(context, "worker_id", request.getSelectedWorkerId());
        putText(context, "worker_lease_id", request.getWorkerLeaseId());
        putStringList(context, "allowed_dirs", request.getAllowedDirs());
        putStringList(context, "allowed_tools", request.getAllowedTools());
        return context;
    }

    private String resolveSkillName(BusinessAgentWorkerTaskLaunchRequest request) {
        return StringUtils.hasText(request.getSkillName()) ? request.getSkillName().trim() : request.getSkillId();
    }

    private void appendLine(StringBuilder builder, String key, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(key).append(": ").append(value.trim()).append('\n');
        }
    }

    private void appendList(StringBuilder builder, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> cleaned = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (!cleaned.isEmpty()) {
            builder.append(key).append(": ").append(String.join(", ", cleaned)).append('\n');
        }
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private void putStringList(Map<String, Object> target, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> cleaned = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (!cleaned.isEmpty()) {
            target.put(key, cleaned);
        }
    }

    private String sanitizeKeyPart(String value) {
        return value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void requireCodexBackend(BusinessAgentWorkerTaskLaunchRequest request) {
        String backend = requireText(request.getWorkerBackend(), "workerBackend is required");
        if (!getWorkerBackend().equals(backend)) {
            throw new IllegalStateException("worker backend mismatch: " + backend);
        }
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
