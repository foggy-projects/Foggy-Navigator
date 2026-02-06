package com.foggy.navigator.agent.framework.skill;

/**
 * Skill 解析器接口
 * 解析 SKILL.md 文件（Claude Code 格式）
 */
public interface SkillParser {

    /**
     * 解析 SKILL.md 内容
     *
     * @param content SKILL.md 文件内容
     * @return 解析后的 Skill
     */
    Skill parse(String content);

    /**
     * 从目录加载 Skill
     * 读取 SKILL.md 和 references/ 目录
     *
     * @param skillPath 技能目录路径
     * @return 解析后的 Skill（包含 references）
     */
    Skill loadFromDirectory(String skillPath);
}
