package com.foggy.navigator.agent.framework.skill;

/**
 * Skill 执行类型
 */
public enum SkillType {
    /**
     * 客户端执行 - 工具调用由前端执行（如创建编码会话后跳转）
     */
    CLIENT,

    /**
     * HTTP 调用 - 通过 REST API 调用后端服务
     */
    HTTP,

    /**
     * Bash 命令 - 执行命令行工具
     */
    BASH,

    /**
     * 纯指令型 - 仅提供 LLM 指令，不涉及工具调用
     */
    INSTRUCTION
}
