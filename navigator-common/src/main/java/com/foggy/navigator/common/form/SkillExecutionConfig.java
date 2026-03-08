package com.foggy.navigator.common.form;

import lombok.Data;

/**
 * Skill 执行配置
 */
@Data
public class SkillExecutionConfig {
    /**
     * 执行逻辑
     */
    private String executionLogic;

    /**
     * 输出格式
     */
    private String outputFormat;

    /**
     * 完整 Markdown 内容
     */
    private String markdownContent;
}
