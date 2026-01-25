package com.foggy.navigator.agent.skill;

import java.util.List;

/**
 * Skill管理器
 */
public interface SkillManager {

    /**
     * 从目录加载Skills
     */
    void loadSkills(String agentId, String directory);

    /**
     * 注册单个Skill
     */
    void registerSkill(Skill skill);

    /**
     * 匹配Skill
     */
    Skill matchSkill(String userMessage, String agentId);

    /**
     * 获取Agent的所有Skill
     */
    List<Skill> getSkillsByAgent(String agentId);

    /**
     * 获取Skill
     */
    Skill getSkill(String skillId);
}
