package com.foggy.navigator.metadata.query.config.service;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.AgentModelOverrideEntity;
import com.foggy.navigator.common.entity.LlmModelConfigEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.form.AgentModelOverrideForm;
import com.foggy.navigator.common.form.LlmModelConfigForm;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.metadata.query.config.repository.AgentModelOverrideRepository;
import com.foggy.navigator.metadata.query.config.repository.LlmModelConfigRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LLM 模型配置管理实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmModelManagerImpl implements LlmModelManager {

    private final LlmModelConfigRepository llmModelRepo;
    private final AgentModelOverrideRepository overrideRepo;
    private final CredentialEncryptor credentialEncryptor;

    // ========== 模型配置 CRUD ==========

    @Override
    @Transactional
    public String saveModelConfig(String tenantId, LlmModelConfigForm form) {
        log.info("Saving LLM model config: tenantId={}, name={}", tenantId, form.getName());

        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTenantId(tenantId);
        entity.setName(form.getName());
        entity.setCategory(form.getCategory());
        entity.setBaseUrl(form.getBaseUrl());
        entity.setModelName(form.getModelName());
        entity.setApiKey(credentialEncryptor.encrypt(form.getApiKey()));
        entity.setIsDefault(form.getIsDefault() != null ? form.getIsDefault() : false);

        // 如果标记为默认，取消同 category 下其他默认
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            clearDefaultForCategory(tenantId, form.getCategory());
        }

        llmModelRepo.save(entity);
        log.info("LLM model config saved: id={}", entity.getId());
        return entity.getId();
    }

    @Override
    @Transactional
    public void updateModelConfig(String id, LlmModelConfigForm form) {
        log.info("Updating LLM model config: id={}", id);

        LlmModelConfigEntity entity = llmModelRepo.findById(id)
                .orElseThrow(() -> RX.throwB("LLM model config not found: " + id));

        if (form.getName() != null) entity.setName(form.getName());
        if (form.getCategory() != null) entity.setCategory(form.getCategory());
        if (form.getBaseUrl() != null) entity.setBaseUrl(form.getBaseUrl());
        if (form.getModelName() != null) entity.setModelName(form.getModelName());
        if (form.getApiKey() != null) {
            entity.setApiKey(credentialEncryptor.encrypt(form.getApiKey()));
        }
        if (form.getIsDefault() != null) {
            if (Boolean.TRUE.equals(form.getIsDefault())) {
                clearDefaultForCategory(entity.getTenantId(), entity.getCategory());
            }
            entity.setIsDefault(form.getIsDefault());
        }

        llmModelRepo.save(entity);
        log.info("LLM model config updated: id={}", id);
    }

    @Override
    @Transactional
    public void deleteModelConfig(String id) {
        log.info("Deleting LLM model config: id={}", id);
        llmModelRepo.deleteById(id);
        log.info("LLM model config deleted: id={}", id);
    }

    @Override
    public List<LlmModelConfigDTO> listModelConfigs(String tenantId) {
        log.debug("Listing LLM model configs: tenantId={}", tenantId);
        return llmModelRepo.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<LlmModelConfigDTO> getModelConfig(String id) {
        log.debug("Getting LLM model config: id={}", id);
        return llmModelRepo.findById(id).map(this::toDTO);
    }

    // ========== 模型选择 ==========

    @Override
    public Optional<LlmModelConfigDTO> resolveModelForAgent(String tenantId, String agentId, LlmModelCategory category) {
        log.debug("Resolving model for agent: tenantId={}, agentId={}, category={}", tenantId, agentId, category);

        // 优先级1：Agent 级别覆盖
        Optional<AgentModelOverrideEntity> override = overrideRepo.findByTenantIdAndAgentId(tenantId, agentId);
        if (override.isPresent()) {
            Optional<LlmModelConfigDTO> overrideModel = getModelConfig(override.get().getModelConfigId());
            if (overrideModel.isPresent()) {
                log.debug("Using agent override model: agentId={}, modelId={}", agentId, override.get().getModelConfigId());
                return overrideModel;
            }
        }

        // 优先级2：category 默认模型
        return getDefaultModel(tenantId, category);
    }

    @Override
    public Optional<LlmModelConfigDTO> getDefaultModel(String tenantId, LlmModelCategory category) {
        log.debug("Getting default model: tenantId={}, category={}", tenantId, category);
        return llmModelRepo.findByTenantIdAndCategoryAndIsDefaultTrue(tenantId, category)
                .map(this::toDTO);
    }

    @Override
    public String getDecryptedApiKey(String modelConfigId) {
        LlmModelConfigEntity entity = llmModelRepo.findById(modelConfigId)
                .orElseThrow(() -> RX.throwB("LLM model config not found: " + modelConfigId));
        return credentialEncryptor.decrypt(entity.getApiKey());
    }

    // ========== Agent 模型覆盖 ==========

    @Override
    @Transactional
    public void setAgentModelOverride(String tenantId, AgentModelOverrideForm form) {
        log.info("Setting agent model override: tenantId={}, agentId={}, modelConfigId={}",
                tenantId, form.getAgentId(), form.getModelConfigId());

        // 验证模型配置存在
        llmModelRepo.findById(form.getModelConfigId())
                .orElseThrow(() -> RX.throwB("LLM model config not found: " + form.getModelConfigId()));

        // upsert: 存在则更新，不存在则创建
        AgentModelOverrideEntity entity = overrideRepo
                .findByTenantIdAndAgentId(tenantId, form.getAgentId())
                .orElseGet(() -> {
                    AgentModelOverrideEntity newEntity = new AgentModelOverrideEntity();
                    newEntity.setId(UUID.randomUUID().toString());
                    newEntity.setTenantId(tenantId);
                    newEntity.setAgentId(form.getAgentId());
                    return newEntity;
                });

        entity.setModelConfigId(form.getModelConfigId());
        overrideRepo.save(entity);
        log.info("Agent model override set: agentId={}", form.getAgentId());
    }

    @Override
    @Transactional
    public void removeAgentModelOverride(String tenantId, String agentId) {
        log.info("Removing agent model override: tenantId={}, agentId={}", tenantId, agentId);
        overrideRepo.deleteByTenantIdAndAgentId(tenantId, agentId);
        log.info("Agent model override removed: agentId={}", agentId);
    }

    @Override
    public List<AgentModelOverrideForm> listAgentModelOverrides(String tenantId) {
        log.debug("Listing agent model overrides: tenantId={}", tenantId);
        return overrideRepo.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .map(entity -> {
                    AgentModelOverrideForm form = new AgentModelOverrideForm();
                    form.setAgentId(entity.getAgentId());
                    form.setModelConfigId(entity.getModelConfigId());
                    return form;
                })
                .collect(Collectors.toList());
    }

    // ========== 状态检查 ==========

    @Override
    public boolean hasAnyModel(String tenantId) {
        return llmModelRepo.existsByTenantId(tenantId);
    }

    // ===== 内部方法 =====

    private void clearDefaultForCategory(String tenantId, LlmModelCategory category) {
        List<LlmModelConfigEntity> defaults = llmModelRepo
                .findByTenantIdAndCategoryOrderByCreatedAtAsc(tenantId, category);
        for (LlmModelConfigEntity e : defaults) {
            if (Boolean.TRUE.equals(e.getIsDefault())) {
                e.setIsDefault(false);
                llmModelRepo.save(e);
            }
        }
    }

    private LlmModelConfigDTO toDTO(LlmModelConfigEntity entity) {
        LlmModelConfigDTO dto = new LlmModelConfigDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        dto.setCategory(entity.getCategory());
        dto.setBaseUrl(entity.getBaseUrl());
        dto.setModelName(entity.getModelName());
        dto.setIsDefault(entity.getIsDefault());
        dto.setHasApiKey(entity.getApiKey() != null && !entity.getApiKey().isEmpty());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
