package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentBundleDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.form.UpstreamAgentForm;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.business.agent.service.worker.PhysicalWorkerRuntimeRegistry;
import com.foggy.navigator.business.agent.service.worker.ResolvedPhysicalWorker;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UpstreamAdminAgentService {

    private final BusinessCodingAgentRepository agentRepository;
    private final BizWorkerPoolRepository workerPoolRepository;
    private final List<PhysicalWorkerRuntimeRegistry> physicalWorkerRuntimeRegistries;
    private final WorkingDirectoryRepository workingDirectoryRepository;
    private final AgentDefaultBindingService agentDefaultBindingService;
    private final LlmModelManager llmModelManager;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<BusinessAgentBundleDTO> list(String tenantId, UpstreamClientAppAdminPrincipal principal) {
        requirePrincipal(principal);
        return agentRepository.findByTenantIdAndOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                        tenantId,
                        ResourceOwnerType.UPSTREAM_SYSTEM,
                        principal.getUpstreamSystemId()).stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public BusinessAgentBundleDTO get(String tenantId,
                                      UpstreamClientAppAdminPrincipal principal,
                                      String agentId) {
        return toDTO(requireSystemOwnedAgent(tenantId, principal, agentId));
    }

    @Transactional
    public BusinessAgentBundleDTO create(String tenantId,
                                         UpstreamClientAppAdminPrincipal principal,
                                         UpstreamAgentForm form) {
        requirePrincipal(principal);
        requireCreateForm(form);
        String agentId = form.getAgentId().trim();
        if (agentRepository.findByAgentIdAndTenantId(agentId, tenantId).isPresent()) {
            throw new IllegalArgumentException("agent already exists: " + agentId);
        }
        CodingAgentEntity entity = new CodingAgentEntity();
        applyForm(tenantId, principal, entity, agentId, form, true);
        CodingAgentEntity saved = agentRepository.save(entity);
        agentDefaultBindingService.ensureDefaults(saved);
        return toDTO(saved);
    }

    @Transactional
    public BusinessAgentBundleDTO update(String tenantId,
                                         UpstreamClientAppAdminPrincipal principal,
                                         String agentId,
                                         UpstreamAgentForm form) {
        CodingAgentEntity entity = requireSystemOwnedAgent(tenantId, principal, agentId);
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        if (StringUtils.hasText(form.getAgentId()) && !entity.getAgentId().equals(form.getAgentId().trim())) {
            throw new IllegalArgumentException("agentId cannot be changed");
        }
        applyForm(tenantId, principal, entity, entity.getAgentId(), form, false);
        CodingAgentEntity saved = agentRepository.save(entity);
        agentDefaultBindingService.ensureDefaults(saved);
        return toDTO(saved);
    }

    private void applyForm(String tenantId,
                           UpstreamClientAppAdminPrincipal principal,
                           CodingAgentEntity entity,
                           String agentId,
                           UpstreamAgentForm form,
                           boolean create) {
        String workerRef = create || StringUtils.hasText(form.getWorkerId())
                ? requireVisibleWorkerRef(tenantId, principal, form.getWorkerId())
                : entity.getWorkerId();
        String defaultDirectoryId = create || StringUtils.hasText(form.getDefaultDirectoryId())
                ? normalizeDefaultDirectoryId(tenantId, principal, form.getDefaultDirectoryId())
                : entity.getDefaultDirectoryId();
        String defaultModelConfigId = create || StringUtils.hasText(form.getDefaultModelConfigId())
                ? normalizeDefaultModelConfigId(tenantId, principal, form.getDefaultModelConfigId())
                : entity.getDefaultModelConfigId();

        entity.setAgentId(agentId);
        entity.setUserId(resolveActorUserId(principal));
        entity.setTenantId(tenantId);
        entity.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        entity.setOwnerId(principal.getUpstreamSystemId());
        entity.setClientAppId(null);
        if (create || StringUtils.hasText(form.getName())) {
            entity.setName(requireText(form.getName(), "name is required"));
        }
        if (create || form.getDescription() != null) {
            entity.setDescription(blankToNull(form.getDescription()));
        }
        if (create || StringUtils.hasText(form.getAgentType())) {
            entity.setAgentType(StringUtils.hasText(form.getAgentType())
                    ? form.getAgentType().trim()
                    : BusinessAgentBundleService.AGENT_TYPE_LANGGRAPH);
        }
        entity.setWorkerId(workerRef);
        entity.setDefaultDirectoryId(defaultDirectoryId);
        entity.setDefaultModelConfigId(defaultModelConfigId);
        if (create || form.getDefaultModel() != null) {
            entity.setDefaultModel(blankToNull(form.getDefaultModel()));
        }
        if (create || form.getSkillsJson() != null) {
            entity.setSkills(normalizeJson(form.getSkillsJson(), "skillsJson", "[]"));
        }
        if (create || form.getAgentProfileJson() != null) {
            entity.setAgentProfile(normalizeJson(
                    form.getAgentProfileJson(),
                    "agentProfileJson",
                    defaultAgentProfile(principal)));
        }
        if (create || form.getEnabled() != null) {
            entity.setEnabled(form.getEnabled() == null || form.getEnabled());
        }
    }

    private CodingAgentEntity requireSystemOwnedAgent(String tenantId,
                                                      UpstreamClientAppAdminPrincipal principal,
                                                      String agentId) {
        requirePrincipal(principal);
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId is required");
        }
        CodingAgentEntity agent = agentRepository.findByAgentIdAndTenantId(agentId.trim(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + agentId));
        if (agent.getOwnerType() != ResourceOwnerType.UPSTREAM_SYSTEM
                || !principal.getUpstreamSystemId().equals(agent.getOwnerId())) {
            throw new SecurityException("agent is not owned by this upstream system");
        }
        return agent;
    }

    private String requireVisibleWorkerRef(String tenantId,
                                           UpstreamClientAppAdminPrincipal principal,
                                           String workerRef) {
        if (!StringUtils.hasText(workerRef)) {
            throw new IllegalArgumentException("workerId is required");
        }
        String normalizedWorkerRef = workerRef.trim();
        return workerPoolRepository.findByPoolIdAndTenantId(normalizedWorkerRef, tenantId)
                .map(pool -> requireVisibleWorkerPool(principal, pool).getPoolId())
                .orElseGet(() -> requireVisiblePhysicalWorker(tenantId, principal, normalizedWorkerRef).workerId());
    }

    private BizWorkerPoolEntity requireVisibleWorkerPool(UpstreamClientAppAdminPrincipal principal,
                                                         BizWorkerPoolEntity pool) {
        if (!BizWorkerPoolService.STATUS_ENABLED.equals(pool.getStatus())) {
            throw new IllegalStateException("worker pool is disabled: " + pool.getPoolId());
        }
        if (pool.getOwnerType() == ResourceOwnerType.PLATFORM) {
            return pool;
        }
        if (pool.getOwnerType() == ResourceOwnerType.UPSTREAM_SYSTEM
                && principal.getUpstreamSystemId().equals(pool.getOwnerId())) {
            return pool;
        }
        throw new SecurityException("worker pool is not visible to this upstream system: " + pool.getPoolId());
    }

    private ResolvedPhysicalWorker requireVisiblePhysicalWorker(String tenantId,
                                                                UpstreamClientAppAdminPrincipal principal,
                                                                String workerId) {
        if (physicalWorkerRuntimeRegistries != null) {
            for (PhysicalWorkerRuntimeRegistry registry : physicalWorkerRuntimeRegistries) {
                if (registry == null) {
                    continue;
                }
                Optional<ResolvedPhysicalWorker> worker = registry.resolve(
                        tenantId,
                        principal.getUpstreamSystemId(),
                        workerId);
                if (worker.isPresent()) {
                    return worker.get();
                }
            }
        }
        throw new IllegalArgumentException("worker not found as worker pool or physical worker: " + workerId);
    }

    private String normalizeDefaultDirectoryId(String tenantId,
                                               UpstreamClientAppAdminPrincipal principal,
                                               String directoryId) {
        if (!StringUtils.hasText(directoryId)) {
            return null;
        }
        WorkingDirectoryEntity directory = workingDirectoryRepository.findByDirectoryId(directoryId.trim())
                .orElseThrow(() -> new IllegalArgumentException("working directory not found: " + directoryId));
        if (!tenantId.equals(directory.getTenantId())) {
            throw new SecurityException("working directory tenant mismatch: " + directory.getDirectoryId());
        }
        if (!Boolean.TRUE.equals(directory.getEnabled())) {
            throw new IllegalStateException("working directory is disabled: " + directory.getDirectoryId());
        }
        if (directory.getOwnerType() != ResourceOwnerType.UPSTREAM_SYSTEM
                || !principal.getUpstreamSystemId().equals(directory.getOwnerId())) {
            throw new SecurityException("working directory is not owned by this upstream system");
        }
        return directory.getDirectoryId();
    }

    private String normalizeDefaultModelConfigId(String tenantId,
                                                 UpstreamClientAppAdminPrincipal principal,
                                                 String modelConfigId) {
        if (!StringUtils.hasText(modelConfigId)) {
            return null;
        }
        LlmModelConfigDTO model = llmModelManager.getModelConfig(modelConfigId.trim())
                .orElseThrow(() -> new IllegalArgumentException("model config not found: " + modelConfigId));
        if (!tenantId.equals(model.getTenantId())) {
            throw new SecurityException("model config tenant mismatch");
        }
        if (model.getOwnerType() != ResourceOwnerType.UPSTREAM_SYSTEM
                || !principal.getUpstreamSystemId().equals(model.getOwnerId())) {
            throw new SecurityException("model config is not owned by this upstream system");
        }
        return model.getId();
    }

    private String normalizeJson(String raw, String fieldName, String defaultJson) {
        if (!StringUtils.hasText(raw)) {
            return defaultJson;
        }
        try {
            return objectMapper.writeValueAsString(objectMapper.readTree(raw));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(fieldName + " must be valid JSON", e);
        }
    }

    private String defaultAgentProfile(UpstreamClientAppAdminPrincipal principal) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "domain", "BUSINESS_AGENT",
                    "kind", "UPSTREAM_SYSTEM_AGENT",
                    "source", "UPSTREAM_ADMIN",
                    "upstreamSystemId", principal.getUpstreamSystemId(),
                    "a2aRoute", true
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid default agent profile", e);
        }
    }

    private BusinessAgentBundleDTO toDTO(CodingAgentEntity entity) {
        return BusinessAgentBundleDTO.fromEntity(entity, entity.getClientAppId(), null, null);
    }

    private void requireCreateForm(UpstreamAgentForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(form.getAgentId(), "agentId is required");
        requireText(form.getName(), "name is required");
        requireText(form.getWorkerId(), "workerId is required");
    }

    private void requirePrincipal(UpstreamClientAppAdminPrincipal principal) {
        if (principal == null || !StringUtils.hasText(principal.getUpstreamSystemId())) {
            throw new SecurityException("upstream admin principal is required");
        }
    }

    private String resolveActorUserId(UpstreamClientAppAdminPrincipal principal) {
        if (StringUtils.hasText(principal.getPrincipalId())) {
            return principal.getPrincipalId();
        }
        if (StringUtils.hasText(principal.getCredentialId())) {
            return principal.getCredentialId();
        }
        return principal.getUpstreamSystemId();
    }

    private String requireText(String value, String message) {
        Assert.hasText(value, message);
        return value.trim();
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
