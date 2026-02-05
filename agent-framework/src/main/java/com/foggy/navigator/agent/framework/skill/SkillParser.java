package com.foggy.navigator.agent.framework.skill;

/**
 * Skill 解析器接口
 * 从 Markdown 文件解析 Skill 定义
 */
public interface SkillParser {

    /**
     * 解析 Markdown 内容为 Skill
     *
     * @param markdownContent Markdown 内容
     * @return 解析后的 Skill
     */
    Skill parse(String markdownContent);

    /**
     * 解析 Markdown 内容为 Skill（带文件名参数）
     *
     * @param markdownContent Markdown 内容
     * @param filename        文件名（用于默认 ID）
     * @return 解析后的 Skill
     */
    default Skill parse(String markdownContent, String filename) {
        Skill skill = parse(markdownContent);
        if (skill.getId() == null || skill.getId().isEmpty()) {
            skill.setId(filename.replace(".md", ""));
            if (skill.getName() == null) {
                skill.setName(skill.getId());
            }
        }
        return skill;
    }

    /**
     * 从文件路径解析 Skill
     *
     * @param filePath 文件路径
     * @return 解析后的 Skill
     */
    Skill parseFile(String filePath);
}
