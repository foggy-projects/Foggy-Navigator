package com.foggy.navigator.agent.skill;

/**
 * Skill解析器
 * 从markdown文件解析Skill定义
 */
public interface SkillParser {

    /**
     * 解析Skill文件
     */
    Skill parse(String markdownContent);

    /**
     * 从文件解析
     */
    Skill parseFile(String filePath);
}
