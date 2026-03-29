package com.foggy.navigator.common.util;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aAgentSkill;
import com.foggy.navigator.common.entity.CodingAgentEntity;

import java.util.List;

/**
 * 从 CodingAgentEntity 构建 A2aAgentCard 的共享工具，
 * 消除 Provider 和 A2aAgent 中的重复构建逻辑。
 */
public final class AgentCardBuilder {

    private AgentCardBuilder() {}

    /**
     * 从 CodingAgentEntity 构建 AgentCard。
     *
     * @param entity     Agent 实体
     * @param skillId    技能 ID（如 "coding"）
     * @param skillDesc  技能描述
     * @param skillTags  技能标签列表
     */
    public static A2aAgentCard fromEntity(CodingAgentEntity entity,
                                          String skillId, String skillDesc, List<String> skillTags) {
        String desc = entity.getDescription();
        if (entity.getProjectSummary() != null) {
            desc = (desc != null ? desc + "\n\n" : "") + "## 项目概述\n" + entity.getProjectSummary();
        }
        return A2aAgentCard.builder()
                .id(entity.getAgentId())
                .name(entity.getName())
                .description(desc)
                .url(entity.getEndpointUrl())
                .version("1.0.0")
                .skills(List.of(
                        A2aAgentSkill.builder()
                                .id(skillId)
                                .name(capitalize(skillId))
                                .description(skillDesc)
                                .tags(skillTags)
                                .build()
                ))
                .build();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
