package com.foggy.navigator.agent.framework.skill;

import org.springframework.core.io.Resource;

import java.io.IOException;

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

    /**
     * 从 Spring Resource 直接解析 Skill（用于 JAR 内资源）
     *
     * @param skillMdResource SKILL.md 的 Resource
     * @param sourcePath 来源路径（仅做记录）
     * @return 解析后的 Skill
     */
    Skill parseResource(Resource skillMdResource, String sourcePath) throws IOException;
}
