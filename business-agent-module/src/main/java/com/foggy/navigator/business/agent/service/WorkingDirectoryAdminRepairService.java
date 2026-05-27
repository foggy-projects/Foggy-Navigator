package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.WorkingDirectoryRepairResultDTO;
import com.foggy.navigator.business.agent.model.form.RepairUpstreamSystemWorkingDirectoryForm;
import com.foggy.navigator.business.agent.repository.BusinessAgentDirectoryBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class WorkingDirectoryAdminRepairService {

    private final WorkingDirectoryRepository directoryRepository;
    private final BusinessCodingAgentRepository agentRepository;
    private final BusinessAgentDirectoryBindingRepository bindingRepository;

    @Transactional
    public WorkingDirectoryRepairResultDTO repairUpstreamSystemDirectory(String directoryId,
                                                                         RepairUpstreamSystemWorkingDirectoryForm form) {
        String normalizedDirectoryId = requireText(directoryId, "directoryId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        String tenantId = requireText(form.getTenantId(), "tenantId is required");
        String upstreamSystemId = requireText(form.getUpstreamSystemId(), "upstreamSystemId is required");
        String rootAgentId = requireText(form.getRootAgentId(), "rootAgentId is required");

        WorkingDirectoryEntity directory = directoryRepository.findByDirectoryId(normalizedDirectoryId)
                .orElseThrow(() -> new IllegalArgumentException("working directory not found: " + normalizedDirectoryId));
        CodingAgentEntity agent = agentRepository.findByAgentIdAndTenantId(rootAgentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + rootAgentId));
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new IllegalStateException("agent is disabled: " + rootAgentId);
        }
        boolean rootAgentOwnerRepaired = false;
        if (agent.getOwnerType() != ResourceOwnerType.UPSTREAM_SYSTEM
                || !upstreamSystemId.equals(agent.getOwnerId())) {
            if (!Boolean.TRUE.equals(form.getRepairRootAgentOwner())) {
                throw new SecurityException("agent is not owned by upstream system: " + rootAgentId
                        + "; set repairRootAgentOwner=true to migrate this legacy root agent owner");
            }
            agent.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
            agent.setOwnerId(upstreamSystemId);
            rootAgentOwnerRepaired = true;
        }

        directory.setTenantId(tenantId);
        directory.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        directory.setOwnerId(upstreamSystemId);
        directory.setClientAppId(null);
        directory.setUpstreamUserId(null);
        directory.setWorkspaceScope(WorkspaceScope.UPSTREAM_SYSTEM_SHARED);
        if (directory.getResolverType() == null) {
            directory.setResolverType(WorkingDirectoryResolverType.DELEGATED);
        }
        if (!StringUtils.hasText(directory.getRootRef())) {
            directory.setRootRef(directory.getPath());
        }
        if (directory.getReadOnly() == null) {
            directory.setReadOnly(false);
        }
        if (directory.getEnabled() == null) {
            directory.setEnabled(true);
        }
        WorkingDirectoryEntity savedDirectory = directoryRepository.save(directory);

        bindingRepository.save(upsertBinding(tenantId, rootAgentId, normalizedDirectoryId));
        boolean setDefault = form.getSetDefaultDirectory() == null || Boolean.TRUE.equals(form.getSetDefaultDirectory());
        if (setDefault) {
            agent.setDefaultDirectoryId(normalizedDirectoryId);
        }
        if (setDefault || rootAgentOwnerRepaired) {
            agentRepository.save(agent);
        }

        WorkingDirectoryRepairResultDTO dto = new WorkingDirectoryRepairResultDTO();
        dto.setDirectoryId(savedDirectory.getDirectoryId());
        dto.setTenantId(savedDirectory.getTenantId());
        dto.setOwnerType(savedDirectory.getOwnerType());
        dto.setOwnerId(savedDirectory.getOwnerId());
        dto.setWorkspaceScope(savedDirectory.getWorkspaceScope());
        dto.setRootAgentId(rootAgentId);
        dto.setRootAgentOwnerType(agent.getOwnerType());
        dto.setRootAgentOwnerId(agent.getOwnerId());
        dto.setRootAgentOwnerRepaired(rootAgentOwnerRepaired);
        dto.setDefaultDirectory(setDefault);
        return dto;
    }

    private AgentDirectoryBindingEntity upsertBinding(String tenantId, String agentId, String directoryId) {
        return bindingRepository.findByTenantIdAndAgentIdAndDirectoryId(tenantId, agentId, directoryId)
                .orElseGet(() -> {
                    AgentDirectoryBindingEntity binding = new AgentDirectoryBindingEntity();
                    binding.setTenantId(tenantId);
                    binding.setAgentId(agentId);
                    binding.setDirectoryId(directoryId);
                    return binding;
                });
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
