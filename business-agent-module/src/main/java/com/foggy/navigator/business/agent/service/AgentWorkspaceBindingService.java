package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.AgentWorkspaceBindingDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentWorkspaceForm;
import com.foggy.navigator.business.agent.repository.BusinessAgentDirectoryBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentWorkspaceBindingService {

    private final BusinessAgentDirectoryBindingRepository bindingRepository;
    private final BusinessCodingAgentRepository agentRepository;
    private final WorkingDirectoryRepository workingDirectoryRepository;
    private final ClientAppService clientAppService;

    @Transactional(readOnly = true)
    public List<AgentWorkspaceBindingDTO> list(String tenantId, String clientAppId, String agentId) {
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        return listBindings(tenantId, agent);
    }

    @Transactional(readOnly = true)
    public List<AgentWorkspaceBindingDTO> listSystemOwned(String tenantId,
                                                         UpstreamClientAppAdminPrincipal principal,
                                                         String agentId) {
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        return listBindings(tenantId, agent);
    }

    @Transactional
    public AgentWorkspaceBindingDTO bind(String tenantId,
                                         String clientAppId,
                                         String agentId,
                                         BindAgentWorkspaceForm form) {
        if (form == null || !StringUtils.hasText(form.getDirectoryId())) {
            throw new IllegalArgumentException("directoryId is required");
        }
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        WorkingDirectoryEntity directory = requireClientAppVisibleDirectory(
                tenantId,
                clientAppId,
                form.getDirectoryId().trim());
        return toDTO(upsertBinding(tenantId, agent.getAgentId(), directory.getDirectoryId()), agent);
    }

    @Transactional
    public AgentWorkspaceBindingDTO bindSystemOwned(String tenantId,
                                                    UpstreamClientAppAdminPrincipal principal,
                                                    String agentId,
                                                    BindAgentWorkspaceForm form) {
        if (form == null || !StringUtils.hasText(form.getDirectoryId())) {
            throw new IllegalArgumentException("directoryId is required");
        }
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        WorkingDirectoryEntity directory = requireUpstreamSystemDirectory(
                tenantId,
                principal,
                form.getDirectoryId().trim());
        return toDTO(upsertBinding(tenantId, agent.getAgentId(), directory.getDirectoryId()), agent);
    }

    @Transactional
    public AgentWorkspaceBindingDTO setDefault(String tenantId,
                                               String clientAppId,
                                               String agentId,
                                               BindAgentWorkspaceForm form) {
        if (form == null || !StringUtils.hasText(form.getDirectoryId())) {
            throw new IllegalArgumentException("directoryId is required");
        }
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        WorkingDirectoryEntity directory = requireClientAppVisibleDirectory(
                tenantId,
                clientAppId,
                form.getDirectoryId().trim());
        return setDefaultDirectory(tenantId, agent, directory.getDirectoryId());
    }

    @Transactional
    public AgentWorkspaceBindingDTO setSystemOwnedDefault(String tenantId,
                                                          UpstreamClientAppAdminPrincipal principal,
                                                          String agentId,
                                                          BindAgentWorkspaceForm form) {
        if (form == null || !StringUtils.hasText(form.getDirectoryId())) {
            throw new IllegalArgumentException("directoryId is required");
        }
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        WorkingDirectoryEntity directory = requireUpstreamSystemDirectory(
                tenantId,
                principal,
                form.getDirectoryId().trim());
        return setDefaultDirectory(tenantId, agent, directory.getDirectoryId());
    }

    @Transactional
    public void unbind(String tenantId, String clientAppId, String agentId, String directoryId) {
        requireText(directoryId, "directoryId is required");
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        deleteBinding(tenantId, agent, directoryId.trim());
    }

    @Transactional
    public void unbindSystemOwned(String tenantId,
                                  UpstreamClientAppAdminPrincipal principal,
                                  String agentId,
                                  String directoryId) {
        requireText(directoryId, "directoryId is required");
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        deleteBinding(tenantId, agent, directoryId.trim());
    }

    private List<AgentWorkspaceBindingDTO> listBindings(String tenantId, CodingAgentEntity agent) {
        return bindingRepository.findByTenantIdAndAgentIdOrderByCreatedAtDesc(tenantId, agent.getAgentId()).stream()
                .map(binding -> toDTO(binding, agent))
                .toList();
    }

    private AgentDirectoryBindingEntity upsertBinding(String tenantId, String agentId, String directoryId) {
        AgentDirectoryBindingEntity binding = bindingRepository.findByTenantIdAndAgentIdAndDirectoryId(
                        tenantId,
                        agentId,
                        directoryId)
                .orElseGet(AgentDirectoryBindingEntity::new);
        binding.setTenantId(tenantId);
        binding.setAgentId(agentId);
        binding.setDirectoryId(directoryId);
        return bindingRepository.save(binding);
    }

    private AgentWorkspaceBindingDTO setDefaultDirectory(String tenantId,
                                                        CodingAgentEntity agent,
                                                        String directoryId) {
        AgentDirectoryBindingEntity binding = upsertBinding(tenantId, agent.getAgentId(), directoryId);
        agent.setDefaultDirectoryId(directoryId);
        CodingAgentEntity saved = agentRepository.save(agent);
        return toDTO(binding, saved);
    }

    private void deleteBinding(String tenantId, CodingAgentEntity agent, String directoryId) {
        if (directoryId.equals(trimToNull(agent.getDefaultDirectoryId()))) {
            throw new IllegalArgumentException("agent default directory cannot be unbound; update agent default directory first");
        }
        bindingRepository.deleteByTenantIdAndAgentIdAndDirectoryId(
                tenantId,
                agent.getAgentId(),
                directoryId);
    }

    private CodingAgentEntity requireClientAppOwnedAgent(String tenantId, String clientAppId, String agentId) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(agentId, "agentId is required");
        clientAppService.requireActiveClientApp(tenantId, clientAppId);
        CodingAgentEntity agent = agentRepository.findByAgentIdAndTenantId(agentId.trim(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + agentId));
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new IllegalStateException("agent is disabled: " + agent.getAgentId());
        }
        if (agent.getOwnerType() != ResourceOwnerType.CLIENT_APP
                || !clientAppId.equals(agent.getOwnerId())
                || !clientAppId.equals(agent.getClientAppId())) {
            throw new SecurityException("agent is not owned by this ClientApp: " + agent.getAgentId());
        }
        return agent;
    }

    private CodingAgentEntity requireSystemOwnedAgent(String tenantId,
                                                      UpstreamClientAppAdminPrincipal principal,
                                                      String agentId) {
        requireText(tenantId, "tenantId is required");
        requireText(agentId, "agentId is required");
        if (principal == null || !StringUtils.hasText(principal.getUpstreamSystemId())) {
            throw new SecurityException("upstream admin principal is required");
        }
        CodingAgentEntity agent = agentRepository.findByAgentIdAndTenantId(agentId.trim(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + agentId));
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new IllegalStateException("agent is disabled: " + agent.getAgentId());
        }
        if (agent.getOwnerType() != ResourceOwnerType.UPSTREAM_SYSTEM
                || !principal.getUpstreamSystemId().equals(agent.getOwnerId())) {
            throw new SecurityException("agent is not owned by this upstream system: " + agent.getAgentId());
        }
        return agent;
    }

    private WorkingDirectoryEntity requireClientAppVisibleDirectory(String tenantId,
                                                                    String clientAppId,
                                                                    String directoryId) {
        WorkingDirectoryEntity directory = requireDirectory(tenantId, directoryId);
        WorkspaceScope scope = directory.getWorkspaceScope();
        if (scope == WorkspaceScope.CLIENT_APP_SHARED) {
            requireDirectoryOwner(directory, ResourceOwnerType.CLIENT_APP, clientAppId);
            if (!clientAppId.equals(directory.getClientAppId())) {
                throw new SecurityException("working directory is not visible to client app: " + directoryId);
            }
            return directory;
        }
        if (scope == WorkspaceScope.USER_PRIVATE) {
            requireDirectoryOwner(directory, ResourceOwnerType.UPSTREAM_USER, directory.getOwnerId());
            if (!clientAppId.equals(directory.getClientAppId())
                    || !StringUtils.hasText(directory.getUpstreamUserId())) {
                throw new SecurityException("working directory is not visible to client app user: " + directoryId);
            }
            return directory;
        }
        throw new SecurityException("working directory scope is not allowed for ClientApp-owned agent: " + directoryId);
    }

    private WorkingDirectoryEntity requireUpstreamSystemDirectory(String tenantId,
                                                                  UpstreamClientAppAdminPrincipal principal,
                                                                  String directoryId) {
        WorkingDirectoryEntity directory = requireDirectory(tenantId, directoryId);
        if (directory.getWorkspaceScope() != WorkspaceScope.UPSTREAM_SYSTEM_SHARED) {
            throw new SecurityException("working directory scope is not allowed for system-owned agent: " + directoryId);
        }
        requireDirectoryOwner(directory, ResourceOwnerType.UPSTREAM_SYSTEM, principal.getUpstreamSystemId());
        return directory;
    }

    private WorkingDirectoryEntity requireDirectory(String tenantId, String directoryId) {
        WorkingDirectoryEntity directory = workingDirectoryRepository.findByDirectoryId(directoryId)
                .orElseThrow(() -> new IllegalArgumentException("working directory not found: " + directoryId));
        if (!tenantId.equals(directory.getTenantId())) {
            throw new SecurityException("working directory tenant mismatch: " + directoryId);
        }
        if (!Boolean.TRUE.equals(directory.getEnabled())) {
            throw new IllegalStateException("working directory is disabled: " + directoryId);
        }
        if (directory.getWorkspaceScope() == null) {
            throw new IllegalStateException("working directory workspaceScope is not configured: " + directoryId);
        }
        return directory;
    }

    private void requireDirectoryOwner(WorkingDirectoryEntity directory,
                                       ResourceOwnerType ownerType,
                                       String ownerId) {
        if (!StringUtils.hasText(ownerId)
                || directory.getOwnerType() != ownerType
                || !ownerId.equals(directory.getOwnerId())) {
            throw new SecurityException("working directory owner mismatch: " + directory.getDirectoryId());
        }
    }

    private AgentWorkspaceBindingDTO toDTO(AgentDirectoryBindingEntity binding, CodingAgentEntity agent) {
        AgentWorkspaceBindingDTO dto = AgentWorkspaceBindingDTO.fromEntity(binding);
        dto.setClientAppId(agent.getClientAppId());
        dto.setDefaultDirectory(binding.getDirectoryId() != null
                && binding.getDirectoryId().equals(trimToNull(agent.getDefaultDirectoryId())));
        workingDirectoryRepository.findByDirectoryId(binding.getDirectoryId()).ifPresent(directory -> {
            dto.setProjectName(directory.getProjectName());
            dto.setRootRef(directory.getRootRef());
            dto.setPath(directory.getPath());
            dto.setWorkspaceScope(directory.getWorkspaceScope());
            dto.setDirectoryOwnerType(directory.getOwnerType());
            dto.setDirectoryOwnerId(directory.getOwnerId());
            dto.setEnabled(directory.getEnabled());
        });
        return dto;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
