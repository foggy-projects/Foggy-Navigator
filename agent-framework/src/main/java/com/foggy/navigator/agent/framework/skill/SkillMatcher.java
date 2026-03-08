package com.foggy.navigator.agent.framework.skill;

import java.util.List;

/**
 * Skill匹配引擎
 */
public interface SkillMatcher {

    /**
     * 匹配Skill
     */
    Skill match(String userMessage, List<Skill> availableSkills);

    /**
     * 计算匹配得分
     */
    double calculateScore(String userMessage, Skill skill);
}
