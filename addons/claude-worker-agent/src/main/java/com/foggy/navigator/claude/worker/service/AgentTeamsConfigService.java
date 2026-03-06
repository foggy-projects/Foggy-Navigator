package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.model.dto.AgentTeamsConfigDTO;
import com.foggy.navigator.claude.worker.model.entity.AgentTeamsConfigEntity;
import com.foggy.navigator.claude.worker.model.form.CreateAgentTeamsConfigForm;
import com.foggy.navigator.claude.worker.model.form.UpdateAgentTeamsConfigForm;
import com.foggy.navigator.claude.worker.repository.AgentTeamsConfigRepository;
import com.foggy.navigator.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent Teams 命名配置管理
 * <p>
 * 一个工作目录可拥有多套命名配置，至多一套标记为默认。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTeamsConfigService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentTeamsConfigRepository configRepository;

    /**
     * 列出指定目录的所有配置
     */
    public List<AgentTeamsConfigDTO> listConfigs(String directoryId, String userId) {
        return configRepository.findByDirectoryIdAndUserIdOrderByCreatedAtAsc(directoryId, userId)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 获取单个配置 DTO
     */
    public AgentTeamsConfigDTO getConfig(String configId, String userId) {
        AgentTeamsConfigEntity entity = configRepository.findByConfigIdAndUserId(configId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent Teams config not found: " + configId));
        return toDTO(entity);
    }

    /**
     * 获取单个配置实体（内部使用）
     */
    public AgentTeamsConfigEntity getConfigEntity(String configId) {
        return configRepository.findByConfigId(configId).orElse(null);
    }

    /**
     * 获取目录的默认配置
     */
    public Optional<AgentTeamsConfigEntity> getDefaultConfig(String directoryId, String userId) {
        return configRepository.findByDirectoryIdAndUserIdAndIsDefaultTrue(directoryId, userId);
    }

    /**
     * 创建新配置
     */
    @Transactional
    public AgentTeamsConfigDTO createConfig(String directoryId, String userId, CreateAgentTeamsConfigForm form) {
        // 验证 JSON 格式
        validateConfigJson(form.getConfig());

        // 如果设为默认，先清除同目录其他默认
        if (Boolean.TRUE.equals(form.getIsDefault())) {
            clearDefault(directoryId, userId);
        }

        AgentTeamsConfigEntity entity = new AgentTeamsConfigEntity();
        entity.setConfigId(IdGenerator.shortId());
        entity.setDirectoryId(directoryId);
        entity.setUserId(userId);
        entity.setName(form.getName());
        entity.setConfig(form.getConfig());
        entity.setIsDefault(Boolean.TRUE.equals(form.getIsDefault()));
        configRepository.save(entity);

        log.info("Agent Teams config created: configId={}, directoryId={}, name={}, isDefault={}",
                entity.getConfigId(), directoryId, form.getName(), entity.getIsDefault());
        return toDTO(entity);
    }

    /**
     * 更新配置
     */
    @Transactional
    public AgentTeamsConfigDTO updateConfig(String configId, String userId, UpdateAgentTeamsConfigForm form) {
        AgentTeamsConfigEntity entity = configRepository.findByConfigIdAndUserId(configId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent Teams config not found: " + configId));

        if (form.getName() != null) {
            entity.setName(form.getName());
        }
        if (form.getConfig() != null) {
            validateConfigJson(form.getConfig());
            entity.setConfig(form.getConfig());
        }
        if (form.getIsDefault() != null) {
            if (Boolean.TRUE.equals(form.getIsDefault())) {
                clearDefault(entity.getDirectoryId(), userId);
            }
            entity.setIsDefault(form.getIsDefault());
        }

        configRepository.save(entity);
        log.info("Agent Teams config updated: configId={}, name={}, isDefault={}",
                configId, entity.getName(), entity.getIsDefault());
        return toDTO(entity);
    }

    /**
     * 删除配置
     */
    @Transactional
    public void deleteConfig(String configId, String userId) {
        AgentTeamsConfigEntity entity = configRepository.findByConfigIdAndUserId(configId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent Teams config not found: " + configId));
        configRepository.delete(entity);
        log.info("Agent Teams config deleted: configId={}, name={}", configId, entity.getName());
    }

    /**
     * 检查目录下是否已有配置（用于数据迁移判断）
     */
    public boolean existsForDirectory(String directoryId, String userId) {
        return configRepository.existsByDirectoryIdAndUserId(directoryId, userId);
    }

    // ========== internal ==========

    /**
     * 清除同目录下的所有默认标记
     */
    private void clearDefault(String directoryId, String userId) {
        configRepository.findByDirectoryIdAndUserIdAndIsDefaultTrue(directoryId, userId)
                .ifPresent(existing -> {
                    existing.setIsDefault(false);
                    configRepository.save(existing);
                });
    }

    /**
     * 验证 config JSON 格式（必须是 JSON 对象）
     */
    private void validateConfigJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Agent Teams config JSON cannot be empty");
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("Agent Teams config must contain at least one agent");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Agent Teams JSON: " + e.getMessage());
        }
    }

    /**
     * 从 config JSON 提取 agent 名称列表
     */
    private List<String> parseAgentNames(String configJson) {
        if (configJson == null || configJson.isBlank()) return List.of();
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(configJson, new TypeReference<>() {});
            return new ArrayList<>(parsed.keySet());
        } catch (Exception e) {
            log.warn("Failed to parse agent names from config JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private AgentTeamsConfigDTO toDTO(AgentTeamsConfigEntity entity) {
        return AgentTeamsConfigDTO.builder()
                .configId(entity.getConfigId())
                .directoryId(entity.getDirectoryId())
                .name(entity.getName())
                .config(entity.getConfig())
                .isDefault(Boolean.TRUE.equals(entity.getIsDefault()))
                .agentNames(parseAgentNames(entity.getConfig()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
