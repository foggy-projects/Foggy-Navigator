package com.foggy.navigator.agent.framework.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Skill 定义（对齐 Claude Code 格式）
 *
 * 目录结构：
 * skill-name/
 * ├── SKILL.md        # 必需，包含 frontmatter + body
 * ├── references/     # 可选，参考文档
 * └── scripts/        # 可选，可执行脚本
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {

    /**
     * 技能名称（必需）
     * 格式：小写字母、数字、连字符，如 "dispatch-coding-task"
     */
    private String name;

    /**
     * 技能描述（必需）
     * 包含：功能描述 + 触发场景（何时使用）
     * 这是 LLM 判断何时触发技能的主要依据
     */
    private String description;

    /**
     * Markdown body 内容
     * 包含执行流程、输入要求、输出格式、约束条件、决策规则等
     */
    private String content;

    /**
     * 技能目录路径
     */
    private String path;

    /**
     * 所属 Agent ID
     */
    private String agentId;

    /**
     * 加载时间
     */
    private LocalDateTime loadedAt;
}
