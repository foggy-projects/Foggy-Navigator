package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.AgentWorkerBindingDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.BindAgentWorkerForm;
import com.foggy.navigator.business.agent.repository.BusinessAgentWorkerBindingRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.common.entity.AgentWorkerBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentWorkerBindingService {

    private final BusinessAgentWorkerBindingRepository bindingRepository;
    private final BusinessCodingAgentRepository agentRepository;
    private final BizWorkerPoolRepository workerPoolRepository;
    private final ClientAppService clientAppService;

    @Transactional(readOnly = true)
    public List<AgentWorkerBindingDTO> list(String tenantId, String clientAppId, String agentId) {
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        return listBindings(tenantId, agent);
    }

    @Transactional(readOnly = true)
    public List<AgentWorkerBindingDTO> listSystemOwned(String tenantId,
                                                       UpstreamClientAppAdminPrincipal principal,
                                                       String agentId) {
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        return listBindings(tenantId, agent);
    }

    @Transactional
    public AgentWorkerBindingDTO bind(String tenantId,
                                      String clientAppId,
                                      String agentId,
                                      BindAgentWorkerForm form) {
        if (form == null || !StringUtils.hasText(form.getWorkerPoolId())) {
            throw new IllegalArgumentException("workerPoolId is required");
        }
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        BizWorkerPoolEntity pool = requireClientAppVisibleWorkerPool(tenantId, clientAppId, form.getWorkerPoolId().trim());
        return toDTO(upsertBinding(tenantId, agent.getAgentId(), pool.getPoolId()), agent);
    }

    @Transactional
    public AgentWorkerBindingDTO bindSystemOwned(String tenantId,
                                                 UpstreamClientAppAdminPrincipal principal,
                                                 String agentId,
                                                 BindAgentWorkerForm form) {
        if (form == null || !StringUtils.hasText(form.getWorkerPoolId())) {
            throw new IllegalArgumentException("workerPoolId is required");
        }
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        BizWorkerPoolEntity pool = requireUpstreamSystemWorkerPool(tenantId, principal, form.getWorkerPoolId().trim());
        return toDTO(upsertBinding(tenantId, agent.getAgentId(), pool.getPoolId()), agent);
    }

    @Transactional
    public AgentWorkerBindingDTO setDefault(String tenantId,
                                            String clientAppId,
                                            String agentId,
                                            BindAgentWorkerForm form) {
        if (form == null || !StringUtils.hasText(form.getWorkerPoolId())) {
            throw new IllegalArgumentException("workerPoolId is required");
        }
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        BizWorkerPoolEntity pool = requireClientAppVisibleWorkerPool(tenantId, clientAppId, form.getWorkerPoolId().trim());
        return setDefaultWorkerPool(tenantId, agent, pool.getPoolId());
    }

    @Transactional
    public AgentWorkerBindingDTO setSystemOwnedDefault(String tenantId,
                                                       UpstreamClientAppAdminPrincipal principal,
                                                       String agentId,
                                                       BindAgentWorkerForm form) {
        if (form == null || !StringUtils.hasText(form.getWorkerPoolId())) {
            throw new IllegalArgumentException("workerPoolId is required");
        }
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        BizWorkerPoolEntity pool = requireUpstreamSystemWorkerPool(tenantId, principal, form.getWorkerPoolId().trim());
        return setDefaultWorkerPool(tenantId, agent, pool.getPoolId());
    }

    @Transactional
    public void unbind(String tenantId, String clientAppId, String agentId, String workerPoolId) {
        requireText(workerPoolId, "workerPoolId is required");
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        deleteBinding(tenantId, agent, workerPoolId.trim());
    }

    @Transactional
    public void unbindSystemOwned(String tenantId,
                                  UpstreamClientAppAdminPrincipal principal,
                                  String agentId,
                                  String workerPoolId) {
        requireText(workerPoolId, "workerPoolId is required");
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        deleteBinding(tenantId, agent, workerPoolId.trim());
    }

    private List<AgentWorkerBindingDTO> listBindings(String tenantId, CodingAgentEntity agent) {
        return bindingRepository.findByTenantIdAndAgentIdOrderByCreatedAtDesc(tenantId, agent.getAgentId()).stream()
                .map(binding -> toDTO(binding, agent))
                .toList();
    }

    private AgentWorkerBindingEntity upsertBinding(String tenantId, String agentId, String workerPoolId) {
        AgentWorkerBindingEntity binding = bindingRepository.findByTenantIdAndAgentIdAndWorkerPoolId(
                        tenantId,
                        agentId,
                        workerPoolId)
                .orElseGet(AgentWorkerBindingEntity::new);
        binding.setTenantId(tenantId);
        binding.setAgentId(agentId);
        binding.setWorkerPoolId(workerPoolId);
        return bindingRepository.save(binding);
    }

    private AgentWorkerBindingDTO setDefaultWorkerPool(String tenantId,
                                                       CodingAgentEntity agent,
                                                       String workerPoolId) {
        AgentWorkerBindingEntity binding = upsertBinding(tenantId, agent.getAgentId(), workerPoolId);
        agent.setWorkerId(workerPoolId);
        CodingAgentEntity saved = agentRepository.save(agent);
        return toDTO(binding, saved);
    }

    private void deleteBinding(String tenantId, CodingAgentEntity agent, String workerPoolId) {
        if (workerPoolId.equals(trimToNull(agent.getWorkerId()))) {
            throw new IllegalArgumentException("agent default worker pool cannot be unbound; update agent default worker pool first");
        }
        bindingRepository.deleteByTenantIdAndAgentIdAndWorkerPoolId(
                tenantId,
                agent.getAgentId(),
                workerPoolId);
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

    private BizWorkerPoolEntity requireClientAppVisibleWorkerPool(String tenantId,
                                                                  String clientAppId,
                                                                  String workerPoolId) {
        ClientAppEntity clientApp = clientAppService.requireActiveClientApp(tenantId, clientAppId);
        BizWorkerPoolEntity pool = requireWorkerPool(tenantId, workerPoolId);
        if (pool.getOwnerType() == ResourceOwnerType.PLATFORM) {
            return pool;
        }
        if (pool.getOwnerType() == ResourceOwnerType.UPSTREAM_SYSTEM
                && StringUtils.hasText(clientApp.getUpstreamSystemId())
                && clientApp.getUpstreamSystemId().equals(pool.getOwnerId())) {
            return pool;
        }
        if (pool.getOwnerType() == ResourceOwnerType.CLIENT_APP
                && clientAppId.equals(pool.getOwnerId())) {
            return pool;
        }
        throw new SecurityException("worker pool is not visible to ClientApp-owned agent: " + workerPoolId);
    }

    private BizWorkerPoolEntity requireUpstreamSystemWorkerPool(String tenantId,
                                                                UpstreamClientAppAdminPrincipal principal,
                                                                String workerPoolId) {
        BizWorkerPoolEntity pool = requireWorkerPool(tenantId, workerPoolId);
        if (pool.getOwnerType() == ResourceOwnerType.PLATFORM) {
            return pool;
        }
        if (pool.getOwnerType() == ResourceOwnerType.UPSTREAM_SYSTEM
                && principal.getUpstreamSystemId().equals(pool.getOwnerId())) {
            return pool;
        }
        throw new SecurityException("worker pool is not visible to system-owned agent: " + workerPoolId);
    }

    private BizWorkerPoolEntity requireWorkerPool(String tenantId, String workerPoolId) {
        BizWorkerPoolEntity pool = workerPoolRepository.findByPoolIdAndTenantId(workerPoolId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("worker pool not found: " + workerPoolId));
        if (!BizWorkerPoolService.STATUS_ENABLED.equals(pool.getStatus())) {
            throw new IllegalStateException("worker pool is disabled: " + workerPoolId);
        }
        if (pool.getOwnerType() == null || !StringUtils.hasText(pool.getOwnerId())) {
            throw new IllegalStateException("worker pool owner is not configured: " + workerPoolId);
        }
        return pool;
    }

    private AgentWorkerBindingDTO toDTO(AgentWorkerBindingEntity binding, CodingAgentEntity agent) {
        AgentWorkerBindingDTO dto = AgentWorkerBindingDTO.fromEntity(binding);
        dto.setClientAppId(agent.getClientAppId());
        dto.setDefaultWorkerPool(binding.getWorkerPoolId() != null
                && binding.getWorkerPoolId().equals(trimToNull(agent.getWorkerId())));
        workerPoolRepository.findByPoolIdAndTenantId(binding.getWorkerPoolId(), binding.getTenantId()).ifPresent(pool -> {
            dto.setWorkerPoolName(pool.getName());
            dto.setWorkerBackend(pool.getWorkerBackend());
            dto.setRoutingPolicy(pool.getRoutingPolicy());
            dto.setWorkerPoolOwnerType(pool.getOwnerType());
            dto.setWorkerPoolOwnerId(pool.getOwnerId());
            dto.setStatus(pool.getStatus());
            dto.setHealthStatus(pool.getHealthStatus());
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
