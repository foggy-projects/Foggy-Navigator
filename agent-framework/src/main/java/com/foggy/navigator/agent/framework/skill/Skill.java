package com.foggy.navigator.agent.framework.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill定义
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    private String id;
    private String name;
    private String agentId;

    /**
     * Skill 执行类型：CLIENT, HTTP, BASH, INSTRUCTION
     */
    @Builder.Default
    private SkillType type = SkillType.INSTRUCTION;

    private List<String> triggerKeywords;
    private List<String> intents;
    private String description;

    /**
     * 执行逻辑（Markdown 格式的指令）
     */
    private String executionLogic;

    /**
     * 输出格式模板
     */
    private String outputFormat;

    /**
     * 分派/委托条件
     */
    private String delegationCondition;

    /**
     * 工具定义（JSON 格式，描述工具参数和调用方式）
     */
    private String toolDefinition;

    /**
     * 原始 Markdown 文件路径
     */
    private String markdownPath;

    /**
     * 原始 Markdown 内容（解析 @import 后的完整内容）
     */
    private String rawContent;

    private LocalDateTime loadedAt;
}
