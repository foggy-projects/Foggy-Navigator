package com.foggy.navigator.common.form;

import lombok.Data;

/**
 * Skill 配置表单（二层结构）
 * 第一层：通用标识
 * 第二层：基本信息、触发配置、执行配置
 */
@Data
public class SkillConfigForm {
    /**
     * Skill ID（新建时可为空，更新时必填）
     */
    private String id;

    /**
     * 租户ID（多租户场景）
     */
    private String tenantId;

    /**
     * Skill 基本信息
     */
    private SkillBasicInfo basicInfo;

    /**
     * Skill 触发配置
     */
    private SkillTriggerConfig triggerConfig;

    /**
     * Skill 执行配置
     */
    private SkillExecutionConfig executionConfig;
}
