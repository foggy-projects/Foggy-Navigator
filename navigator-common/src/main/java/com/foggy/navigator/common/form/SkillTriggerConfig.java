package com.foggy.navigator.common.form;

import lombok.Data;

import java.util.List;

/**
 * Skill 触发配置
 */
@Data
public class SkillTriggerConfig {
    /**
     * 触发关键词列表
     */
    private List<String> keywords;

    /**
     * 意图列表
     */
    private List<String> intents;

    /**
     * 分派条件
     */
    private String delegationCondition;
}
