package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.AgentModelBindingDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.BindAgentModelForm;
import com.foggy.navigator.business.agent.repository.BusinessAgentModelBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.AgentModelBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentModelBindingService {

    private final BusinessAgentModelBindingRepository bindingRepository;
    private final BusinessCodingAgentRepository agentRepository;
    private final ClientAppService clientAppService;
    private final ClientAppModelConfigGrantService modelConfigGrantService;
    private final LlmModelManager llmModelManager;

    @Transactional(readOnly = true)
    public List<AgentModelBindingDTO> list(String tenantId, String clientAppId, String agentId) {
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        return listBindings(tenantId, agent);
    }

    @Transactional(readOnly = true)
    public List<AgentModelBindingDTO> listSystemOwned(String tenantId,
                                                      UpstreamClientAppAdminPrincipal principal,
                                                      String agentId) {
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        return listBindings(tenantId, agent);
    }

    @Transactional
    public AgentModelBindingDTO bind(String tenantId,
                                     String clientAppId,
                                     String agentId,
                                     BindAgentModelForm form) {
        if (form == null || !StringUtils.hasText(form.getModelConfigId())) {
            throw new IllegalArgumentException("modelConfigId is required");
        }
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        String modelConfigId = modelConfigGrantService.resolveEffectiveModelConfigId(
                tenantId,
                clientAppId,
                form.getModelConfigId().trim());
        return toDTO(upsertBinding(tenantId, agent.getAgentId(), modelConfigId), agent);
    }

    @Transactional
    public AgentModelBindingDTO bindSystemOwned(String tenantId,
                                                UpstreamClientAppAdminPrincipal principal,
                                                String agentId,
                                                BindAgentModelForm form) {
        if (form == null || !StringUtils.hasText(form.getModelConfigId())) {
            throw new IllegalArgumentException("modelConfigId is required");
        }
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        String modelConfigId = requireUpstreamSystemModel(tenantId, principal, form.getModelConfigId().trim()).getId();
        return toDTO(upsertBinding(tenantId, agent.getAgentId(), modelConfigId), agent);
    }

    @Transactional
    public AgentModelBindingDTO setDefault(String tenantId,
                                           String clientAppId,
                                           String agentId,
                                           BindAgentModelForm form) {
        if (form == null || !StringUtils.hasText(form.getModelConfigId())) {
            throw new IllegalArgumentException("modelConfigId is required");
        }
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        String modelConfigId = modelConfigGrantService.resolveEffectiveModelConfigId(
                tenantId,
                clientAppId,
                form.getModelConfigId().trim());
        return setDefaultModel(tenantId, agent, modelConfigId);
    }

    @Transactional
    public AgentModelBindingDTO setSystemOwnedDefault(String tenantId,
                                                      UpstreamClientAppAdminPrincipal principal,
                                                      String agentId,
                                                      BindAgentModelForm form) {
        if (form == null || !StringUtils.hasText(form.getModelConfigId())) {
            throw new IllegalArgumentException("modelConfigId is required");
        }
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        String modelConfigId = requireUpstreamSystemModel(tenantId, principal, form.getModelConfigId().trim()).getId();
        return setDefaultModel(tenantId, agent, modelConfigId);
    }

    @Transactional
    public void unbind(String tenantId, String clientAppId, String agentId, String modelConfigId) {
        requireText(modelConfigId, "modelConfigId is required");
        CodingAgentEntity agent = requireClientAppOwnedAgent(tenantId, clientAppId, agentId);
        String normalizedModelConfigId = modelConfigId.trim();
        if (normalizedModelConfigId.equals(trimToNull(agent.getDefaultModelConfigId()))) {
            throw new IllegalArgumentException("agent default model cannot be unbound; update agent default model first");
        }
        bindingRepository.deleteByTenantIdAndAgentIdAndModelConfigId(
                tenantId,
                agent.getAgentId(),
                normalizedModelConfigId);
    }

    @Transactional
    public void unbindSystemOwned(String tenantId,
                                  UpstreamClientAppAdminPrincipal principal,
                                  String agentId,
                                  String modelConfigId) {
        requireText(modelConfigId, "modelConfigId is required");
        CodingAgentEntity agent = requireSystemOwnedAgent(tenantId, principal, agentId);
        String normalizedModelConfigId = modelConfigId.trim();
        if (normalizedModelConfigId.equals(trimToNull(agent.getDefaultModelConfigId()))) {
            throw new IllegalArgumentException("agent default model cannot be unbound; update agent default model first");
        }
        bindingRepository.deleteByTenantIdAndAgentIdAndModelConfigId(
                tenantId,
                agent.getAgentId(),
                normalizedModelConfigId);
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

    private List<AgentModelBindingDTO> listBindings(String tenantId, CodingAgentEntity agent) {
        return bindingRepository.findByTenantIdAndAgentIdOrderByCreatedAtDesc(tenantId, agent.getAgentId()).stream()
                .map(binding -> toDTO(binding, agent))
                .toList();
    }

    private AgentModelBindingEntity upsertBinding(String tenantId, String agentId, String modelConfigId) {
        AgentModelBindingEntity binding = bindingRepository.findByTenantIdAndAgentIdAndModelConfigId(
                        tenantId,
                        agentId,
                        modelConfigId)
                .orElseGet(AgentModelBindingEntity::new);
        binding.setTenantId(tenantId);
        binding.setAgentId(agentId);
        binding.setModelConfigId(modelConfigId);
        return bindingRepository.save(binding);
    }

    private AgentModelBindingDTO setDefaultModel(String tenantId, CodingAgentEntity agent, String modelConfigId) {
        AgentModelBindingEntity binding = upsertBinding(tenantId, agent.getAgentId(), modelConfigId);
        agent.setDefaultModelConfigId(modelConfigId);
        CodingAgentEntity saved = agentRepository.save(agent);
        return toDTO(binding, saved);
    }

    private LlmModelConfigDTO requireUpstreamSystemModel(String tenantId,
                                                        UpstreamClientAppAdminPrincipal principal,
                                                        String modelConfigId) {
        LlmModelConfigDTO model = llmModelManager.getModelConfig(modelConfigId)
                .orElseThrow(() -> new IllegalArgumentException("model config not found: " + modelConfigId));
        if (!tenantId.equals(model.getTenantId())) {
            throw new IllegalArgumentException("model config tenant mismatch");
        }
        if (model.getOwnerType() != ResourceOwnerType.UPSTREAM_SYSTEM
                || !principal.getUpstreamSystemId().equals(model.getOwnerId())) {
            throw new SecurityException("model config is not owned by this upstream system");
        }
        if (Boolean.FALSE.equals(model.getEnabled())) {
            throw new IllegalArgumentException("model config is disabled");
        }
        return model;
    }

    private AgentModelBindingDTO toDTO(AgentModelBindingEntity binding, CodingAgentEntity agent) {
        AgentModelBindingDTO dto = AgentModelBindingDTO.fromEntity(binding);
        dto.setClientAppId(agent.getClientAppId());
        dto.setDefaultModel(binding.getModelConfigId() != null
                && binding.getModelConfigId().equals(trimToNull(agent.getDefaultModelConfigId())));
        llmModelManager.getModelConfig(binding.getModelConfigId()).ifPresent(model -> enrich(dto, model));
        return dto;
    }

    private void enrich(AgentModelBindingDTO dto, LlmModelConfigDTO model) {
        dto.setModelConfigName(model.getName());
        dto.setWorkerBackend(model.getWorkerBackend());
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
