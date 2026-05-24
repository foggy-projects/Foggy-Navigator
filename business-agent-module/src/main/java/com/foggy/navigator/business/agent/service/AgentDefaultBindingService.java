package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.repository.BusinessAgentDirectoryBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentModelBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentWorkerBindingRepository;
import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import com.foggy.navigator.common.entity.AgentModelBindingEntity;
import com.foggy.navigator.common.entity.AgentWorkerBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AgentDefaultBindingService {

    private final BusinessAgentModelBindingRepository modelBindingRepository;
    private final BusinessAgentDirectoryBindingRepository directoryBindingRepository;
    private final BusinessAgentWorkerBindingRepository workerBindingRepository;

    @Transactional
    public void ensureDefaults(CodingAgentEntity agent) {
        if (agent == null || !StringUtils.hasText(agent.getTenantId()) || !StringUtils.hasText(agent.getAgentId())) {
            throw new IllegalArgumentException("agent tenantId and agentId are required");
        }
        ensureModelBinding(agent);
        ensureDirectoryBinding(agent);
        ensureWorkerBinding(agent);
    }

    private void ensureModelBinding(CodingAgentEntity agent) {
        String modelConfigId = trimToNull(agent.getDefaultModelConfigId());
        if (modelConfigId == null) {
            return;
        }
        boolean exists = modelBindingRepository.findByTenantIdAndAgentIdAndModelConfigId(
                agent.getTenantId(),
                agent.getAgentId(),
                modelConfigId).isPresent();
        if (exists) {
            return;
        }
        AgentModelBindingEntity binding = new AgentModelBindingEntity();
        binding.setTenantId(agent.getTenantId());
        binding.setAgentId(agent.getAgentId());
        binding.setModelConfigId(modelConfigId);
        modelBindingRepository.save(binding);
    }

    private void ensureDirectoryBinding(CodingAgentEntity agent) {
        String directoryId = trimToNull(agent.getDefaultDirectoryId());
        if (directoryId == null) {
            return;
        }
        boolean exists = directoryBindingRepository.findByTenantIdAndAgentIdAndDirectoryId(
                agent.getTenantId(),
                agent.getAgentId(),
                directoryId).isPresent();
        if (exists) {
            return;
        }
        AgentDirectoryBindingEntity binding = new AgentDirectoryBindingEntity();
        binding.setTenantId(agent.getTenantId());
        binding.setAgentId(agent.getAgentId());
        binding.setDirectoryId(directoryId);
        directoryBindingRepository.save(binding);
    }

    private void ensureWorkerBinding(CodingAgentEntity agent) {
        String workerPoolId = trimToNull(agent.getWorkerId());
        if (workerPoolId == null) {
            return;
        }
        boolean exists = workerBindingRepository.findByTenantIdAndAgentIdAndWorkerPoolId(
                agent.getTenantId(),
                agent.getAgentId(),
                workerPoolId).isPresent();
        if (exists) {
            return;
        }
        AgentWorkerBindingEntity binding = new AgentWorkerBindingEntity();
        binding.setTenantId(agent.getTenantId());
        binding.setAgentId(agent.getAgentId());
        binding.setWorkerPoolId(workerPoolId);
        workerBindingRepository.save(binding);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
