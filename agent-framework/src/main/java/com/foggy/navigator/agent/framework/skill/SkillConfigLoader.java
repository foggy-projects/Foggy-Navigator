package com.foggy.navigator.agent.framework.skill;

import java.util.List;

/**
 * Skill 配置加载器接口
 * 支持从数据库或其他配置源加载 Skill
 */
public interface SkillConfigLoader {

    /**
     * 加载 Agent 可用的所有 Skill
     * @param agentId Agent ID
     * @param tenantId 租户 ID（可选）
     * @return Skill 列表
     */
    List<Skill> loadSkillsForAgent(String agentId, String tenantId);

    /**
     * 加载单个 Skill
     * @param skillId Skill ID
     * @return Skill（不存在时返回 null）
     */
    Skill loadSkill(String skillId);

    /**
     * 刷新 Agent 的 Skill 缓存
     * @param agentId Agent ID
     */
    void refreshSkills(String agentId);
}
