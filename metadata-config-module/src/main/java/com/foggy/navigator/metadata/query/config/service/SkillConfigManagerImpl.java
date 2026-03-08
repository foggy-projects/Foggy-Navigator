package com.foggy.navigator.metadata.query.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.SkillConfigDTO;
import com.foggy.navigator.common.entity.SkillConfigEntity;
import com.foggy.navigator.common.enums.SkillScope;
import com.foggy.navigator.common.enums.SkillStatus;
import com.foggy.navigator.common.form.SkillBasicInfo;
import com.foggy.navigator.common.form.SkillConfigForm;
import com.foggy.navigator.common.form.SkillExecutionConfig;
import com.foggy.navigator.common.form.SkillTriggerConfig;
import com.foggy.navigator.metadata.query.config.repository.SkillConfigRepository;
import com.foggy.navigator.spi.config.SkillConfigManager;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Skill 配置管理实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillConfigManagerImpl implements SkillConfigManager {

    private final SkillConfigRepository skillConfigRepo;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public String saveSkillConfig(SkillConfigForm form) {
        String name = form.getBasicInfo() != null ? form.getBasicInfo().getName() : "unknown";
        log.info("Saving skill config: name={}", name);

        SkillConfigEntity entity = new SkillConfigEntity();
        entity.setId(form.getId() != null ? form.getId() : UUID.randomUUID().toString());
        entity.setTenantId(form.getTenantId());

        // 基本信息
        if (form.getBasicInfo() != null) {
            SkillBasicInfo basic = form.getBasicInfo();
            entity.setName(basic.getName());
            entity.setDescription(basic.getDescription());
            entity.setScope(basic.getScope() != null ? basic.getScope() : SkillScope.GLOBAL);
            entity.setAgentId(basic.getAgentId());
            entity.setStatus(basic.getStatus() != null ? basic.getStatus() : SkillStatus.DRAFT);
            entity.setPriority(basic.getPriority() != null ? basic.getPriority() : 100);
        }

        // 触发配置
        if (form.getTriggerConfig() != null) {
            SkillTriggerConfig trigger = form.getTriggerConfig();
            entity.setTriggerKeywords(toJson(trigger.getKeywords()));
            entity.setIntents(toJson(trigger.getIntents()));
            entity.setDelegationCondition(trigger.getDelegationCondition());
        }

        // 执行配置
        if (form.getExecutionConfig() != null) {
            SkillExecutionConfig exec = form.getExecutionConfig();
            entity.setExecutionLogic(exec.getExecutionLogic());
            entity.setOutputFormat(exec.getOutputFormat());
            entity.setMarkdownContent(exec.getMarkdownContent());
        }

        skillConfigRepo.save(entity);
        log.info("Skill config saved: id={}", entity.getId());
        return entity.getId();
    }

    @Override
    @Transactional
    public void updateSkillConfig(String skillId, SkillConfigForm form) {
        log.info("Updating skill config: id={}", skillId);

        SkillConfigEntity entity = skillConfigRepo.findById(skillId)
            .orElseThrow(() -> RX.throwB("Skill config not found: " + skillId));

        // 更新基本信息
        if (form.getBasicInfo() != null) {
            SkillBasicInfo basic = form.getBasicInfo();
            if (basic.getName() != null) entity.setName(basic.getName());
            if (basic.getDescription() != null) entity.setDescription(basic.getDescription());
            if (basic.getScope() != null) entity.setScope(basic.getScope());
            if (basic.getAgentId() != null) entity.setAgentId(basic.getAgentId());
            if (basic.getStatus() != null) entity.setStatus(basic.getStatus());
            if (basic.getPriority() != null) entity.setPriority(basic.getPriority());
        }

        // 更新触发配置
        if (form.getTriggerConfig() != null) {
            SkillTriggerConfig trigger = form.getTriggerConfig();
            if (trigger.getKeywords() != null) entity.setTriggerKeywords(toJson(trigger.getKeywords()));
            if (trigger.getIntents() != null) entity.setIntents(toJson(trigger.getIntents()));
            if (trigger.getDelegationCondition() != null) entity.setDelegationCondition(trigger.getDelegationCondition());
        }

        // 更新执行配置
        if (form.getExecutionConfig() != null) {
            SkillExecutionConfig exec = form.getExecutionConfig();
            if (exec.getExecutionLogic() != null) entity.setExecutionLogic(exec.getExecutionLogic());
            if (exec.getOutputFormat() != null) entity.setOutputFormat(exec.getOutputFormat());
            if (exec.getMarkdownContent() != null) entity.setMarkdownContent(exec.getMarkdownContent());
        }

        skillConfigRepo.save(entity);
        log.info("Skill config updated: id={}", skillId);
    }

    @Override
    @Transactional
    public void updateSkillStatus(String skillId, SkillStatus status) {
        log.info("Updating skill status: id={}, status={}", skillId, status);
        int updated = skillConfigRepo.updateStatus(skillId, status);
        if (updated == 0) {
            throw RX.throwB("Skill config not found: " + skillId);
        }
    }

    @Override
    @Transactional
    public void deleteSkillConfig(String skillId) {
        log.info("Deleting skill config: id={}", skillId);
        skillConfigRepo.deleteById(skillId);
        log.info("Skill config deleted: id={}", skillId);
    }

    @Override
    public List<SkillConfigDTO> getSkillsForAgent(String agentId, String tenantId) {
        log.debug("Getting skills for agent: agentId={}, tenantId={}", agentId, tenantId);
        List<SkillConfigEntity> entities = skillConfigRepo.findAvailableSkillsForAgent(
            agentId, tenantId, SkillStatus.ENABLED);
        return entities.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    @Override
    public List<SkillConfigDTO> getSkillsByScope(SkillScope scope, String tenantId) {
        log.debug("Getting skills by scope: scope={}, tenantId={}", scope, tenantId);
        List<SkillConfigEntity> entities;
        if (scope == SkillScope.TENANT && tenantId != null) {
            entities = skillConfigRepo.findByScopeAndTenantIdAndStatusOrderByPriorityAsc(
                scope, tenantId, SkillStatus.ENABLED);
        } else {
            entities = skillConfigRepo.findByScopeAndStatusOrderByPriorityAsc(scope, SkillStatus.ENABLED);
        }
        return entities.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    @Override
    public Optional<SkillConfigDTO> getSkillConfig(String skillId) {
        log.debug("Getting skill config: id={}", skillId);
        return skillConfigRepo.findById(skillId).map(this::toDTO);
    }

    @Override
    public Optional<SkillConfigDTO> getSkillByName(String name, String tenantId) {
        log.debug("Getting skill by name: name={}, tenantId={}", name, tenantId);
        Optional<SkillConfigEntity> entity;
        if (tenantId != null) {
            entity = skillConfigRepo.findByNameAndTenantId(name, tenantId);
        } else {
            entity = skillConfigRepo.findByNameAndTenantIdIsNull(name);
        }
        return entity.map(this::toDTO);
    }

    // ===== 转换方法 =====

    private SkillConfigDTO toDTO(SkillConfigEntity entity) {
        SkillConfigDTO dto = new SkillConfigDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setScope(entity.getScope());
        dto.setAgentId(entity.getAgentId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setTriggerKeywords(fromJson(entity.getTriggerKeywords()));
        dto.setIntents(fromJson(entity.getIntents()));
        dto.setExecutionLogic(entity.getExecutionLogic());
        dto.setOutputFormat(entity.getOutputFormat());
        dto.setDelegationCondition(entity.getDelegationCondition());
        dto.setMarkdownContent(entity.getMarkdownContent());
        dto.setStatus(entity.getStatus());
        dto.setPriority(entity.getPriority());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize list to JSON", e);
            return null;
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize JSON to list", e);
            return Collections.emptyList();
        }
    }
}
