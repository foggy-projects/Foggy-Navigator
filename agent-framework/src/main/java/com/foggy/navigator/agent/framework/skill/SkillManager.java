package com.foggy.navigator.agent.framework.skill;

import java.util.List;

/**
 * Skill 管理器
 * 负责加载、注册、匹配 Skill
 */
public interface SkillManager {

    /**
     * 从目录加载 Skills
     *
     * @param agentId   Agent ID
     * @param directory Skill 目录路径（文件系统或 classpath:）
     */
    void loadSkills(String agentId, String directory);

    /**
     * 注册单个 Skill
     */
    void registerSkill(Skill skill);

    /**
     * 匹配 Skill
     */
    Skill matchSkill(String userMessage, String agentId);

    /**
     * 获取 Agent 的所有 Skill
     */
    List<Skill> getSkillsByAgent(String agentId);

    /**
     * 根据名称获取 Skill
     */
    Skill getSkillByName(String agentId, String skillName);
}
