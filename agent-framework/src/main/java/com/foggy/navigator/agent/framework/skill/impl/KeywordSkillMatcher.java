package com.foggy.navigator.agent.framework.skill.impl;

import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.agent.framework.skill.SkillMatcher;

import java.util.List;

/**
 * 基于描述的 Skill 匹配器
 * 使用简单的关键词匹配，作为 LlmSkillMatcher 的回退方案
 */
public class KeywordSkillMatcher implements SkillMatcher {

    @Override
    public Skill match(String userMessage, List<Skill> availableSkills) {
        if (availableSkills == null || availableSkills.isEmpty()) {
            return null;
        }

        String messageLower = userMessage.toLowerCase();
        Skill bestMatch = null;
        double bestScore = 0;

        for (Skill skill : availableSkills) {
            double score = calculateScore(messageLower, skill);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = skill;
            }
        }

        // 只有分数超过阈值才返回
        return bestScore > 0.1 ? bestMatch : null;
    }

    @Override
    public double calculateScore(String userMessage, Skill skill) {
        String messageLower = userMessage.toLowerCase();
        double score = 0;

        // 基于 name 匹配（skill 名称通常与功能相关）
        if (skill.getName() != null && messageLower.contains(skill.getName().toLowerCase())) {
            score += 0.5;
        }

        // 基于 description 中的关键词匹配
        if (skill.getDescription() != null) {
            String descLower = skill.getDescription().toLowerCase();
            String[] words = messageLower.split("\\s+");
            int matchCount = 0;
            for (String word : words) {
                if (word.length() > 2 && descLower.contains(word)) {
                    matchCount++;
                }
            }
            if (words.length > 0) {
                score += 0.5 * matchCount / words.length;
            }
        }

        return score;
    }
}
