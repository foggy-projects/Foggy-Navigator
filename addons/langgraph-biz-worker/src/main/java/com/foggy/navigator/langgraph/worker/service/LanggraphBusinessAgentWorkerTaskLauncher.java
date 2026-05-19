package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLauncher;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.model.form.CreateLanggraphTaskForm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LanggraphBusinessAgentWorkerTaskLauncher implements BusinessAgentWorkerTaskLauncher {

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
        form.setAgentId(request.getSkillId());
        form.setSkillName(skillName);
        form.setWorkerId(member.getWorkerId());
        form.setSessionId(request.getSessionId());
        form.setContextId(request.getContextId());
        form.setModelConfigId(request.getModelConfigId());
        form.setPrompt("Business Agent task " + request.getBusinessTaskId()
                + ". Use the business function tools when user intent requires controlled business execution.");
        form.setContext(buildContext(request));
        form.setRuntimeContext(buildRuntimeContext(request));

        LanggraphTaskDTO workerTask = taskService.createTask(request.getActorUserId(), request.getTenantId(), form);
        return BusinessAgentWorkerTaskLaunchResult.builder()
                .workerTaskId(workerTask.getTaskId())
                .workerSessionId(workerTask.getSessionId())
                .workerId(member.getWorkerId())
                .providerType(LanggraphTaskService.PROVIDER_TYPE)
                .build();
    }

    private Map<String, Object> buildContext(BusinessAgentWorkerTaskLaunchRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("businessTaskId", request.getBusinessTaskId());
        putText(context, "contextId", request.getContextId());
        putText(context, "context_id", request.getContextId());
        putText(context, "session_id", request.getSessionId());
        context.put("clientAppId", request.getClientAppId());
        context.put("upstreamUserId", request.getUpstreamUserId());
        context.put("accountId", request.getUpstreamUserId());
        context.put("account_id", request.getUpstreamUserId());
        context.put("workerPoolId", request.getWorkerPoolId());
        context.put("workerBackend", request.getWorkerBackend());
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

    private Map<String, Object> buildRuntimeContext(BusinessAgentWorkerTaskLaunchRequest request) {
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        Map<String, Object> runtimeContext = new LinkedHashMap<>();
        if (StringUtils.hasText(request.getTaskScopedToken())) {
            runtimeContext.put("task_scoped_token", request.getTaskScopedToken());
        }
        putText(runtimeContext, "skill_name", resolveSkillName(request));
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
        putText(policy, "workdir", request.getWorkdir());
        putStringList(policy, "allowed_dirs", request.getAllowedDirs());
        putStringList(policy, "allowed_tools", request.getAllowedTools());
        return policy;
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
