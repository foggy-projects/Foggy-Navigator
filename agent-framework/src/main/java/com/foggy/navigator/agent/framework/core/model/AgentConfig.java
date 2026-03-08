package com.foggy.navigator.agent.framework.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Agent配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConfig {

    // 基本信息
    private String id;
    private String name;
    private String type;  // system / user
    private String description;

    // 能力声明
    private List<String> capabilities;

    // Skills配置
    private SkillsConfig skills;

    // 工具配置
    private List<ToolConfig> tools;

    // 模型配置
    private ModelConfig model;

    // 分派规则
    private DelegationConfig delegation;

    // 会话恢复配置
    private SessionResumeConfig sessionResume;

    // 扩展配置
    private Map<String, Object> extensions;
}
