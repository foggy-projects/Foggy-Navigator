package com.foggy.navigator.spi.config;

import com.foggy.navigator.common.dto.SkillConfigDTO;
import com.foggy.navigator.common.enums.SkillScope;
import com.foggy.navigator.common.enums.SkillStatus;
import com.foggy.navigator.common.form.SkillConfigForm;

import java.util.List;
import java.util.Optional;

/**
 * Skill 配置管理接口（SPI）
 * 提供 Skill 配置的增删改查能力
 *
 * 设计原则：
 * - 使用 Form/DTO 而非 Entity 作为参数/返回值
 * - 支持部分更新（只传需要修改的字段）
 * - 支持多作用域（SYSTEM, GLOBAL, AGENT, TENANT）
 */
public interface SkillConfigManager {

    /**
     * 保存 Skill 配置
     * @param form Skill 配置表单
     * @return 保存后的 Skill ID
     */
    String saveSkillConfig(SkillConfigForm form);

    /**
     * 更新 Skill 配置
     * @param skillId Skill ID
     * @param form Skill 配置表单（仅更新非 null 字段）
     */
    void updateSkillConfig(String skillId, SkillConfigForm form);

    /**
     * 更新 Skill 状态
     * @param skillId Skill ID
     * @param status 新状态
     */
    void updateSkillStatus(String skillId, SkillStatus status);

    /**
     * 删除 Skill 配置
     * @param skillId Skill ID
     */
    void deleteSkillConfig(String skillId);

    /**
     * 获取 Agent 可用的所有 Skill（包含 SYSTEM, GLOBAL, TENANT 和该 Agent 专属的 Skill）
     * @param agentId Agent ID
     * @param tenantId 租户 ID（可选）
     * @return Skill 配置列表
     */
    List<SkillConfigDTO> getSkillsForAgent(String agentId, String tenantId);

    /**
     * 按作用域获取 Skill 列表
     * @param scope Skill 作用域
     * @param tenantId 租户 ID（TENANT 作用域时必填）
     * @return Skill 配置列表
     */
    List<SkillConfigDTO> getSkillsByScope(SkillScope scope, String tenantId);

    /**
     * 获取单个 Skill 配置
     * @param skillId Skill ID
     * @return Skill 配置（不存在时返回空）
     */
    Optional<SkillConfigDTO> getSkillConfig(String skillId);

    /**
     * 根据名称查找 Skill（用于去重检查）
     * @param name Skill 名称
     * @param tenantId 租户 ID
     * @return Skill 配置（不存在时返回空）
     */
    Optional<SkillConfigDTO> getSkillByName(String name, String tenantId);
}
