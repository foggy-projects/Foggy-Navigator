package com.foggy.navigator.agent.skill.impl;

import com.foggy.navigator.agent.skill.Skill;
import com.foggy.navigator.agent.skill.SkillMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于关键词的Skill匹配器
 */
@Component
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
        int matchCount = 0;
        int totalKeywords = 0;

        // 检查触发关键词
        if (skill.getTriggerKeywords() != null) {
            totalKeywords += skill.getTriggerKeywords().size();
            for (String keyword : skill.getTriggerKeywords()) {
                if (messageLower.contains(keyword.toLowerCase())) {
                    matchCount++;
                }
            }
        }

        // 检查意图
        if (skill.getIntents() != null) {
            totalKeywords += skill.getIntents().size();
            for (String intent : skill.getIntents()) {
                if (messageLower.contains(intent.toLowerCase())) {
                    matchCount++;
                }
            }
        }

        if (totalKeywords > 0) {
            score = (double) matchCount / totalKeywords;
        }

        return score;
    }
}
