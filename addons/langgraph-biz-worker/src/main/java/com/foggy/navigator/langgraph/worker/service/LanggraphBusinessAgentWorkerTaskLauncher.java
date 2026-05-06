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

import java.util.LinkedHashMap;
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
        form.setWorkerId(member.getWorkerId());
        form.setSessionId(request.getSessionId());
        form.setModelConfigId(request.getModelConfigId());
        form.setPrompt("Business Agent task " + request.getBusinessTaskId()
                + " for skill " + request.getSkillId()
                + ". Use the business function tools when user intent requires controlled business execution.");
        form.setContext(buildContext(request));

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
        context.put("clientAppId", request.getClientAppId());
        context.put("upstreamUserId", request.getUpstreamUserId());
        context.put("skillId", request.getSkillId());
        context.put("workerPoolId", request.getWorkerPoolId());
        context.put("workerBackend", request.getWorkerBackend());
        return context;
    }
}
