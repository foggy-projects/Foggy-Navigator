package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.BusinessAgentSessionService;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLauncher;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class LanggraphBusinessAgentWorkerTaskLauncher implements BusinessAgentWorkerTaskLauncher {

    private static final Duration CONTEXT_ALLOCATION_TIMEOUT = Duration.ofSeconds(10);

    private final BizWorkerPoolMemberRepository poolMemberRepository;
    private final LanggraphWorkerService workerService;
    private final LanggraphTaskService taskService;

    @Override
    public String getWorkerBackend() {
        return ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND;
    }

    @Override
    public BusinessAgentWorkerTaskLaunchResult launch(BusinessAgentWorkerTaskLaunchRequest request) {
        BizWorkerPoolMemberEntity member = poolMemberRepository.findByPoolIdOrderByCreatedAtAsc(request.getWorkerPoolId())
                .stream()
                .filter(item -> BizWorkerPoolService.STATUS_ENABLED.equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("worker pool has no enabled members: " + request.getWorkerPoolId()));

        LanggraphWorkerEntity worker = workerService.getWorkerEntity(member.getWorkerId());
        if (StringUtils.hasText(worker.getTenantId()) && !Objects.equals(worker.getTenantId(), request.getTenantId())) {
            throw new SecurityException("worker tenant mismatch");
        }

        CreateLanggraphTaskForm form = new CreateLanggraphTaskForm();
        String skillName = resolveSkillName(request);
        form.setAgentId(request.getAgentId());
        form.setWorkerId(member.getWorkerId());
        form.setSessionId(request.getSessionId());
        String contextId = resolveContextId(worker, request.getContextId());
        form.setContextId(contextId);
        form.setModelConfigId(request.getModelConfigId());
        form.setModel(request.getModel());
        form.setPrompt("Business Agent task " + request.getBusinessTaskId()
                + ". Use the business function tools when user intent requires controlled business execution.");
        form.setContext(buildContext(request, skillName, contextId));
        form.setRuntimeContext(buildRuntimeContext(request, skillName));

        LanggraphTaskDTO workerTask = taskService.createTask(request.getActorUserId(), request.getTenantId(), form);
        return BusinessAgentWorkerTaskLaunchResult.builder()
                .workerTaskId(workerTask.getTaskId())
                .workerSessionId(workerTask.getSessionId())
                .contextId(contextId)
                .workerId(member.getWorkerId())
                .providerType(LanggraphTaskService.PROVIDER_TYPE)
                .build();
    }

    private String resolveContextId(LanggraphWorkerEntity worker, String requestedContextId) {
        if (StringUtils.hasText(requestedContextId)) {
            return requestedContextId.trim();
        }
        Map<String, Object> response;
        try {
            response = workerService.createClient(worker)
                    .allocateContext()
                    .block(CONTEXT_ALLOCATION_TIMEOUT);
        } catch (WebClientResponseException.NotFound ex) {
            String contextId = BusinessAgentSessionService.generateContextId();
            log.info("LangGraph BizWorker does not expose context allocation route; generated contextId in Navi: workerId={}",
                    worker.getWorkerId());
            return contextId;
        }
        Object contextId = response != null ? response.get("contextId") : null;
        if (contextId instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        throw new IllegalStateException("LangGraph BizWorker did not allocate contextId");
    }

    private Map<String, Object> buildContext(BusinessAgentWorkerTaskLaunchRequest request, String skillName, String contextId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("businessTaskId", request.getBusinessTaskId());
        putText(context, "contextId", contextId);
        putText(context, "context_id", contextId);
        putText(context, "session_id", request.getSessionId());
        context.put("clientAppId", request.getClientAppId());
        putText(context, "businessAgentId", request.getAgentId());
        putText(context, "businessSkillId", request.getSkillId());
        putText(context, "businessSkillName", skillName);
        context.put("upstreamUserId", request.getUpstreamUserId());
        context.put("accountId", request.getUpstreamUserId());
        context.put("account_id", request.getUpstreamUserId());
        putText(context, "directoryId", request.getDirectoryId());
        putText(context, "workingDirectoryId", request.getDirectoryId());
        putText(context, "workspaceScope", request.getWorkspaceScope());
        putText(context, "workspaceResolverType", request.getWorkspaceResolverType());
        if (request.getWorkspaceReadOnly() != null) {
            context.put("workspaceReadOnly", request.getWorkspaceReadOnly());
        }
        context.put("workerPoolId", request.getWorkerPoolId());
        context.put("workerBackend", request.getWorkerBackend());
        context.put("auto_inject_app_public_skills", true);
        if (request.getMarkdownBody() != null && !request.getMarkdownBody().isBlank()) {
            context.put("skill_markdown", request.getMarkdownBody());
        }
        return context;
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private Map<String, Object> buildRuntimeContext(BusinessAgentWorkerTaskLaunchRequest request, String skillName) {
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        Map<String, Object> runtimeContext = new LinkedHashMap<>();
        if (StringUtils.hasText(request.getTaskScopedToken())) {
            runtimeContext.put("task_scoped_token", request.getTaskScopedToken());
        }
        putText(runtimeContext, "skill_name", skillName);
        if (StringUtils.hasText(request.getVisionModelConfigId())) {
            runtimeContext.put("vision_model_config_id", request.getVisionModelConfigId());
        }
        Map<String, Object> executionPolicy = buildExecutionPolicy(request);
        if (!executionPolicy.isEmpty()) {
            runtimeContext.put("execution_policy", executionPolicy);
        }
        runtimeContext.put("current_time", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        runtimeContext.put("timezone", zoneId.getId());
        runtimeContext.put("business_date", now.toLocalDate().toString());
        return runtimeContext;
    }

    private String resolveSkillName(BusinessAgentWorkerTaskLaunchRequest request) {
        if (StringUtils.hasText(request.getSkillName())) {
            return request.getSkillName().trim();
        }
        return request.getSkillId();
    }

    private Map<String, Object> buildExecutionPolicy(BusinessAgentWorkerTaskLaunchRequest request) {
        Map<String, Object> policy = new LinkedHashMap<>();
        putText(policy, "directory_id", request.getDirectoryId());
        putText(policy, "workspace_scope", request.getWorkspaceScope());
        putText(policy, "workspace_resolver_type", request.getWorkspaceResolverType());
        if (request.getWorkspaceReadOnly() != null) {
            policy.put("read_only", request.getWorkspaceReadOnly());
        }
        putObject(policy, "quota_policy", request.getWorkspaceQuotaPolicy());
        putObject(policy, "retention_policy", request.getWorkspaceRetentionPolicy());
        putObject(policy, "concurrency_policy", request.getWorkspaceConcurrencyPolicy());
        putText(policy, "workdir", request.getWorkdir());
        putStringList(policy, "allowed_dirs", request.getAllowedDirs());
        putStringList(policy, "allowed_tools", request.getAllowedTools());
        return policy;
    }

    private void putObject(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
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
}
