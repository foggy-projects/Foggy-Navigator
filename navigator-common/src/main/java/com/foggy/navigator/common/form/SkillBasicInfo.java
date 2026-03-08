package com.foggy.navigator.common.form;

import com.foggy.navigator.common.enums.SkillScope;
import com.foggy.navigator.common.enums.SkillStatus;
import lombok.Data;

/**
 * Skill 基本信息
 */
@Data
public class SkillBasicInfo {
    /**
     * Skill 名称
     */
    private String name;

    /**
     * Skill 描述
     */
    private String description;

    /**
     * Skill 作用域
     */
    private SkillScope scope;

    /**
     * Agent ID（AGENT作用域时必填）
     */
    private String agentId;

    /**
     * Skill 状态
     */
    private SkillStatus status;

    /**
     * 优先级（数值越小优先级越高）
     */
    private Integer priority;
}
