package com.foggy.navigator.agent.framework.core;

import com.foggy.navigator.agent.framework.core.model.AgentConfig;

/**
 * Agent配置加载器
 * 主要格式：JSON（用于数据库存储、API交互、前端编辑）
 * 辅助格式：YAML（用于导入导出、版本控制）
 */
public interface AgentConfigLoader {

    /**
     * 从JSON字符串加载Agent配置
     */
    AgentConfig loadFromJson(String json) throws AgentConfigException;

    /**
     * 从YAML字符串加载Agent配置
     */
    AgentConfig loadFromYaml(String yaml) throws AgentConfigException;

    /**
     * 从指定路径加载Agent配置（自动检测格式）
     */
    AgentConfig load(String path) throws AgentConfigException;

    /**
     * 验证配置的有效性
     */
    ValidationResult validate(AgentConfig config);

    /**
     * 将配置导出为JSON
     */
    String toJson(AgentConfig config);

    /**
     * 将配置导出为YAML
     */
    String toYaml(AgentConfig config);
}
